#include "ocr_inference.h"
#include <android/log.h>
#include <fstream>
#include <cstring>
#include <optional>
#include <dlfcn.h>
#include <thread>
#include <future>
#include <mutex> // Added for singleton synchronization
#include <sys/mman.h>

// LiteRT Next C++ API headers
#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_tensor_buffer.h"
#include "litert/cc/litert_common.h"
#include "litert/cc/litert_options.h"
#include "litert/cc/litert_buffer_ref.h"

#include "litert/c/litert_common.h"

#define LOG_TAG "Yomihon_Inference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace mihon {

// Global static environment that survives OcrInference lifecycle
// This prevents the GPU delegate context from being destroyed and failing to re-init
static std::optional<litert::Environment> g_persist_env;
static std::mutex g_env_mutex;

// Helper to log duration with a consistent message format
static void LogDurationMs(const char* label, const std::chrono::steady_clock::time_point& start) {
    const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();
    LOGI("%s took %lld ms", label, ms);
}

// Helper to free model assets from RAM
static void ReleaseSystemPages(const void* ptr, size_t size) {
    // align ptr to page size (usually 4kb)
    auto addr = reinterpret_cast<uintptr_t>(ptr);
    size_t page_size = sysconf(_SC_PAGESIZE);
    uintptr_t base = addr & ~(page_size - 1);

    // Calculate new size including the offset
    size_t len = size + (addr - base);

    int result = madvise(reinterpret_cast<void*>(base), len, MADV_DONTNEED);
    if (result != 0) {
        LOGW("Failed to madvise pages: %s", strerror(errno));
    } else {
        LOGI("Released %zu bytes of model data from RAM cache", size);
    }
}

int OcrInference::GetOptimalThreadCount() noexcept {
    const unsigned int hw_threads = std::thread::hardware_concurrency();
    if (hw_threads == 0) return 2; // Fallback if detection fails
    return static_cast<int>(std::min(hw_threads, 4u));
}

// Internal structure to hold LiteRT objects
struct OcrInference::LiteRtObjects {
    std::optional<litert::Environment> cpu_env; // Dedicated environment for CPU
    std::optional<litert::CompiledModel> compiled_encoder;
    std::optional<litert::CompiledModel> compiled_decoder;

    std::vector<litert::TensorBuffer> encoder_input_buffers;
    std::vector<litert::TensorBuffer> encoder_output_buffers;
    std::vector<litert::TensorBuffer> decoder_input_buffers;
    std::vector<litert::TensorBuffer> decoder_output_buffers;

    // Pre-allocated output buffers for reading
    std::vector<float> encoder_hidden_states;
    std::vector<float> decoder_logits;

    // Track whether we're using GPU or CPU - per-model and overall
    bool using_gpu = false;
    bool encoder_using_gpu = false;
    bool decoder_using_gpu = false;
};

OcrInference::OcrInference() = default;

OcrInference::~OcrInference() {
    Close();
}

bool OcrInference::Initialize(
    AAsset* encoder_asset,
    AAsset* decoder_asset,
    AAsset* embeddings_asset,
    const char* cache_dir,
    const char* native_lib_dir
) {
    if (initialized_) {
        LOGE("OcrInference already initialized");
        return false;
    }

    const auto overall_init_start = std::chrono::steady_clock::now();

    try {
        // Take ownership of model assets to prevent destruction
        encoder_asset_ = encoder_asset;
        decoder_asset_ = decoder_asset;
        embeddings_asset_ = embeddings_asset;

        const auto* encoder_data = static_cast<const uint8_t*>(AAsset_getBuffer(encoder_asset_));
        size_t encoder_size = AAsset_getLength(encoder_asset_);

        const auto* decoder_data = static_cast<const uint8_t*>(AAsset_getBuffer(decoder_asset_));
        size_t decoder_size = AAsset_getLength(decoder_asset_);

        const void* raw_emb_buffer = AAsset_getBuffer(embeddings_asset_);
        size_t raw_emb_size = AAsset_getLength(embeddings_asset_);

        embeddings_data_ = static_cast<const float*>(raw_emb_buffer);
        embedding_count_ = raw_emb_size / sizeof(float);

        litert_ = std::make_unique<LiteRtObjects>();

        {
            std::lock_guard<std::mutex> lock(g_env_mutex);

            if (!g_persist_env.has_value()) {
                const auto env_start = std::chrono::steady_clock::now();

                std::vector<litert::Environment::Option> env_options;
                env_options.push_back({
                    litert::Environment::OptionTag::DispatchLibraryDir,
                    litert::LiteRtVariant{native_lib_dir}
                });

                auto env_result = litert::Environment::Create(env_options);
                if (!env_result.HasValue()) {
                    LOGE("Failed to create LiteRT environment: %s",
                         env_result.Error().Message().c_str());
                    return false;
                }
                g_persist_env.emplace(std::move(env_result.Value()));
                LogDurationMs("LiteRT Environment creation (Global)", env_start);
            }
        }

        bool opencl_available = false;
        bool compiled = false;

        void* opencl_lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
        if (!opencl_lib) opencl_lib = dlopen("libOpenCL-pixel.so", RTLD_NOW | RTLD_GLOBAL);
        if (!opencl_lib) opencl_lib = dlopen("libOpenCL-car.so", RTLD_NOW | RTLD_GLOBAL);
        if (!opencl_lib) opencl_lib = dlopen("/vendor/lib64/libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);

        if (opencl_lib) {
            opencl_available = true;
            dlclose(opencl_lib);
        } else {
            LOGW("OpenCL library not found. Falling back to CPU.");
        }

        if (opencl_available) {
            compiled = TryCompileWithGpu(encoder_data, encoder_size, decoder_data, decoder_size);
            if (compiled) {
                // Release encoder and decoder assets, as they are no longer needed
                ReleaseSystemPages(encoder_data, encoder_size);
                ReleaseSystemPages(decoder_data, decoder_size);
            } else {
                LOGW("GPU compilation failed, attempting CPU compilation...");
            }
        }

        if (!compiled) {
            compiled = TryCompileWithCpu(encoder_data, encoder_size, decoder_data, decoder_size);
            if (!compiled) {
                LOGE("CPU compilation failed. Unable to initialize model.");
                return false;
            }
        }

        litert_->using_gpu = (litert_->encoder_using_gpu && litert_->decoder_using_gpu);

        if (!CreateBuffers()) {
            LOGE("Failed to create buffers");
            return false;
        }

        if (!PerformWarmup()) {
            LOGE("Model warmup failed; unable to verify execution");
            return false;
        }

        embeddings_input_.resize(MAX_SEQUENCE_LENGTH * HIDDEN_SIZE, 0.0f);
        attention_mask_.resize(MAX_SEQUENCE_LENGTH, 0.0f);

        initialized_ = true;
        LogDurationMs("Overall OcrInference Initialize", overall_init_start);

        const char* encoder_accel = litert_->encoder_using_gpu ? "GPU" : "CPU";
        const char* decoder_accel = litert_->decoder_using_gpu ? "GPU" : "CPU";
        LOGI("Accelerator Config: Encoder=%s, Decoder=%s", encoder_accel, decoder_accel);

        return true;
    } catch (const std::exception& e) {
        LOGE("Exception during initialization: %s", e.what());
        Close();
        return false;
    }
}

bool OcrInference::CreateBuffers() {
    const auto start = std::chrono::steady_clock::now();

    auto encoder_input_result = litert_->compiled_encoder->CreateInputBuffers();
    if (!encoder_input_result.HasValue()) {
        LOGE("Failed to create encoder input buffers: %s",
             encoder_input_result.Error().Message().c_str());
        return false;
    }
    litert_->encoder_input_buffers = std::move(encoder_input_result.Value());

    auto encoder_output_result = litert_->compiled_encoder->CreateOutputBuffers();
    if (!encoder_output_result.HasValue()) {
        LOGE("Failed to create encoder output buffers: %s",
             encoder_output_result.Error().Message().c_str());
        return false;
    }
    litert_->encoder_output_buffers = std::move(encoder_output_result.Value());

    auto decoder_input_result = litert_->compiled_decoder->CreateInputBuffers();
    if (!decoder_input_result.HasValue()) {
        LOGE("Failed to create decoder input buffers: %s",
             decoder_input_result.Error().Message().c_str());
        return false;
    }
    litert_->decoder_input_buffers = std::move(decoder_input_result.Value());

    auto decoder_output_result = litert_->compiled_decoder->CreateOutputBuffers();
    if (!decoder_output_result.HasValue()) {
        LOGE("Failed to create decoder output buffers: %s",
             decoder_output_result.Error().Message().c_str());
        return false;
    }
    litert_->decoder_output_buffers = std::move(decoder_output_result.Value());

    auto encoder_out_size_result = litert_->encoder_output_buffers[0].Size();
    if (encoder_out_size_result.HasValue()) {
        encoder_output_size_ = encoder_out_size_result.Value() / sizeof(float);
    } else {
        LOGE("Failed to get encoder output buffer size");
        return false;
    }

    auto decoder_out_size_result = litert_->decoder_output_buffers[0].Size();
    if (decoder_out_size_result.HasValue()) {
        decoder_output_size_ = decoder_out_size_result.Value() / sizeof(float);
    } else {
        LOGE("Failed to get decoder output buffer size");
        return false;
    }

    litert_->encoder_hidden_states.resize(encoder_output_size_);
    litert_->decoder_logits.resize(decoder_output_size_);

    LogDurationMs("CreateBuffers overhead", start);

    return true;
}

bool OcrInference::PerformWarmup() {
    const auto warmup_start = std::chrono::steady_clock::now();

    std::vector<float> dummy_image(IMAGE_SIZE * IMAGE_SIZE * 3, 0.0f);

    auto write_result = litert_->encoder_input_buffers[0].Write<float>(
        absl::MakeConstSpan(dummy_image)
    );
    if (!write_result.HasValue()) {
        LOGE("Warmup: Failed to write encoder input");
        return false;
    }

    auto encoder_run_result = litert_->compiled_encoder->Run(
        litert_->encoder_input_buffers,
        litert_->encoder_output_buffers
    );
    if (!encoder_run_result.HasValue()) {
        LOGE("Warmup: Failed to run encoder: %s", encoder_run_result.Error().Message().c_str());
        return false;
    }

    auto encoder_output_bytes = litert_->encoder_output_buffers[0].Size();
    size_t encoder_output_floats = 0;
    if (encoder_output_bytes.HasValue()) {
        encoder_output_floats = encoder_output_bytes.Value() / sizeof(float);
    }
    if (encoder_output_floats == 0) {
        LOGE("Warmup: Encoder output buffer size is 0");
        return false;
    }

    std::vector<float> warmup_hidden_states(encoder_output_floats, 0.0f);
    auto warmup_read = litert_->encoder_output_buffers[0].Read<float>(
        absl::MakeSpan(warmup_hidden_states)
    );
    if (!warmup_read.HasValue()) {
        LOGE("Warmup: Failed to read encoder output for decoder warmup");
        return false;
    }

    std::vector<float> warmup_attention(MAX_SEQUENCE_LENGTH, 0.0f);
    std::vector<float> warmup_embeddings(MAX_SEQUENCE_LENGTH * HIDDEN_SIZE, 0.0f);
    warmup_attention[0] = 1.0f;

    auto write_hidden_result = litert_->decoder_input_buffers[0].Write<float>(
        absl::MakeConstSpan(warmup_hidden_states)
    );
    if (!write_hidden_result.HasValue()) {
        LOGE("Warmup: Failed to write decoder hidden states input");
        return false;
    }

    auto write_mask_result = litert_->decoder_input_buffers[1].Write<float>(
        absl::MakeConstSpan(warmup_attention)
    );
    if (!write_mask_result.HasValue()) {
        LOGE("Warmup: Failed to write decoder attention mask input");
        return false;
    }

    auto write_embeddings_result = litert_->decoder_input_buffers[2].Write<float>(
        absl::MakeConstSpan(warmup_embeddings)
    );
    if (!write_embeddings_result.HasValue()) {
        LOGE("Warmup: Failed to write decoder embeddings input");
        return false;
    }

    auto decoder_run_result = litert_->compiled_decoder->Run(
        litert_->decoder_input_buffers,
        litert_->decoder_output_buffers
    );
    if (!decoder_run_result.HasValue()) {
        LOGE("Warmup: Failed to run decoder: %s", decoder_run_result.Error().Message().c_str());
        return false;
    }

    LogDurationMs("PerformWarmup total", warmup_start);
    return true;
}

bool OcrInference::TryCompileWithGpu(const uint8_t* encoder_data, size_t encoder_size, const uint8_t* decoder_data, size_t decoder_size) {
    const auto try_compile_start = std::chrono::steady_clock::now();

    auto options_result = litert::Options::Create();
    if (!options_result.HasValue()) {
        LOGW("Failed to create options for GPU compilation");
        return false;
    }
    auto options = std::move(options_result.Value());
    auto hw_result = options.SetHardwareAccelerators(litert::HwAccelerators::kGpu);
    if (!hw_result.HasValue()) {
        LOGW("Failed to set hardware accelerators: %s", hw_result.Error().Message().c_str());
        return false;
    }
    auto gpu_opts_result = options.GetGpuOptions();
    if (gpu_opts_result.HasValue()) {
        auto& gpu_opts = gpu_opts_result.Value();
        gpu_opts.SetPrecision(litert::GpuOptions::Precision::kFp16);
    }

    // Prepare options for Decoder
    auto decoder_options_result = litert::Options::Create();
    if (!decoder_options_result.HasValue()) {
        LOGW("Failed to create options for decoder GPU compilation");
        return false;
    }
    auto decoder_options = std::move(decoder_options_result.Value());
    decoder_options.SetHardwareAccelerators(litert::HwAccelerators::kGpu);
    auto decoder_gpu_opts_result = decoder_options.GetGpuOptions();
    if (decoder_gpu_opts_result.HasValue()) {
        auto& decoder_gpu_opts = decoder_gpu_opts_result.Value();
        decoder_gpu_opts.SetPrecision(litert::GpuOptions::Precision::kFp16);
    }

    // Launch Encoder compilation asynchronously
    auto encoder_future = std::async(std::launch::async, [encoder_data, encoder_size, opts = std::move(options)]() mutable {
        const auto encoder_compile_start = std::chrono::steady_clock::now();
        auto result = litert::CompiledModel::Create(
            *g_persist_env,
            litert::BufferRef<uint8_t>(encoder_data, encoder_size),
            opts
        );
        LogDurationMs("Encoder GPU compile (Async)", encoder_compile_start);
        return result;
    });

    // Compile Decoder on the main thread
    const auto decoder_compile_start = std::chrono::steady_clock::now();
    auto compiled_decoder_result = litert::CompiledModel::Create(
        *g_persist_env,
        litert::BufferRef<uint8_t>(decoder_data, decoder_size),
        decoder_options
    );
    LogDurationMs("Decoder GPU compile (Main Thread)", decoder_compile_start);

    // Wait for Encoder compilation
    auto compiled_encoder_result = encoder_future.get();

    // Check Encoder results
    if (!compiled_encoder_result.HasValue()) {
        const auto& error = compiled_encoder_result.Error();
        LOGW("Failed to compile encoder with GPU: status=%d, message=%s",
             static_cast<int>(error.StatusCC()), error.Message().c_str());
        return false;
    }

    auto encoder_accel_result = compiled_encoder_result.Value().IsFullyAccelerated();
    if (encoder_accel_result.HasValue() && !encoder_accel_result.Value()) {
        LOGW("Encoder is not fully GPU-accelerated");
        return false;
    }

    // Check Decoder results
    if (!compiled_decoder_result.HasValue()) {
        const auto& error = compiled_decoder_result.Error();
        LOGW("Failed to compile decoder with GPU: status=%d, message=%s",
             static_cast<int>(error.StatusCC()), error.Message().c_str());
        return false;
    }

    auto decoder_accel_result = compiled_decoder_result.Value().IsFullyAccelerated();
    if (decoder_accel_result.HasValue() && !decoder_accel_result.Value()) {
        LOGW("Decoder is not fully GPU-accelerated");
        return false;
    }

    litert_->compiled_encoder.emplace(std::move(compiled_encoder_result.Value()));
    litert_->compiled_decoder.emplace(std::move(compiled_decoder_result.Value()));
    litert_->encoder_using_gpu = true;
    litert_->decoder_using_gpu = true;
    litert_->using_gpu = true;

    LogDurationMs("TryCompileWithGpu total (Parallel)", try_compile_start);
    return true;
}

bool OcrInference::TryCompileWithCpu(const uint8_t* encoder_data, size_t encoder_size, const uint8_t* decoder_data, size_t decoder_size) {
    const auto try_compile_start = std::chrono::steady_clock::now();
    const int num_threads = GetOptimalThreadCount(); // Revert to optimal threads
    LOGI("Attempting CPU compilation with %d threads", num_threads);

    // Create a separate environment for CPU
    if (!litert_->cpu_env.has_value()) {
        auto env_result = litert::Environment::Create({});
        if (!env_result.HasValue()) {
            LOGE("Failed to create CPU LiteRT environment: %s", env_result.Error().Message().c_str());
            return false;
        }
        litert_->cpu_env.emplace(std::move(env_result.Value()));
    }

    auto encoder_options_result = litert::Options::Create();
    if (!encoder_options_result.HasValue()) {
        LOGE("Failed to create options for encoder CPU compilation");
        return false;
    }
    auto encoder_options = std::move(encoder_options_result.Value());

    auto hw_result = encoder_options.SetHardwareAccelerators(litert::HwAccelerators::kCpu);
    if (!hw_result.HasValue()) {
        LOGE("Failed to set CPU hardware accelerator for encoder: %s", hw_result.Error().Message().c_str());
        return false;
    }

    auto encoder_cpu_opts_result = encoder_options.GetCpuOptions();
    if (encoder_cpu_opts_result.HasValue()) {
        encoder_cpu_opts_result.Value().SetNumThreads(num_threads);
    }

    auto decoder_options_result = litert::Options::Create();
    if (!decoder_options_result.HasValue()) {
        LOGE("Failed to create options for decoder CPU compilation");
        return false;
    }
    auto decoder_options = std::move(decoder_options_result.Value());

    auto decoder_hw_result = decoder_options.SetHardwareAccelerators(litert::HwAccelerators::kCpu);
    if (!decoder_hw_result.HasValue()) {
        LOGE("Failed to set CPU hardware accelerator for decoder: %s", decoder_hw_result.Error().Message().c_str());
        return false;
    }

    auto decoder_cpu_opts_result = decoder_options.GetCpuOptions();
    if (decoder_cpu_opts_result.HasValue()) {
        decoder_cpu_opts_result.Value().SetNumThreads(num_threads);
    }

    // Compile Encoder synchronously on the calling thread (to ensure thread affinity)
    const auto encoder_compile_start = std::chrono::steady_clock::now();
    auto compiled_encoder_result = litert::CompiledModel::Create(
        *litert_->cpu_env,
        litert::BufferRef<uint8_t>(encoder_data, encoder_size),
        encoder_options
    );
    LogDurationMs("Encoder CPU compile (Sync)", encoder_compile_start);

    // Compile Decoder synchronously
    const auto decoder_compile_start = std::chrono::steady_clock::now();
    auto compiled_decoder_result = litert::CompiledModel::Create(
        *litert_->cpu_env,
        litert::BufferRef<uint8_t>(decoder_data, decoder_size),
        decoder_options
    );
    LogDurationMs("Decoder CPU compile (Sync)", decoder_compile_start);

    // Check Encoder results
    if (!compiled_encoder_result.HasValue()) {
        const auto& error = compiled_encoder_result.Error();
        LOGE("Failed to compile encoder with CPU: status=%d, message=%s",
             static_cast<int>(error.StatusCC()), error.Message().c_str());
        return false;
    }

    // Check Decoder results
    if (!compiled_decoder_result.HasValue()) {
        const auto& error = compiled_decoder_result.Error();
        LOGE("Failed to compile decoder with CPU: status=%d, message=%s",
             static_cast<int>(error.StatusCC()), error.Message().c_str());
        return false;
    }

    litert_->compiled_encoder.emplace(std::move(compiled_encoder_result.Value()));
    litert_->compiled_decoder.emplace(std::move(compiled_decoder_result.Value()));
    litert_->encoder_using_gpu = false;
    litert_->decoder_using_gpu = false;
    litert_->using_gpu = false;

    LOGI("CPU compilation successful with %d threads", num_threads);
    LogDurationMs("TryCompileWithCpu total (Sequential)", try_compile_start);
    return true;
}

bool OcrInference::IsEncoderUsingGpu() const {
    if (!litert_) return false;
    return litert_->encoder_using_gpu;
}

bool OcrInference::IsDecoderUsingGpu() const {
    if (!litert_) return false;
    return litert_->decoder_using_gpu;
}

void OcrInference::UpdateEmbedding(int token_id, int index) noexcept {
    const int embed_offset = token_id * HIDDEN_SIZE;
    const int output_offset = index * HIDDEN_SIZE;
    std::memcpy(
        embeddings_input_.data() + output_offset,
        embeddings_data_ + embed_offset,
        HIDDEN_SIZE * sizeof(float)
    );
}

int OcrInference::FindMaxLogitToken(int seq_len) const noexcept {
    const int last_token_pos = seq_len - 1;
    const float* logits = litert_->decoder_logits.data() + (last_token_pos * VOCAB_SIZE);

    float max_logit = logits[0];
    int max_token = 0;

    for (int vocab_idx = 1; vocab_idx < VOCAB_SIZE; ++vocab_idx) {
        const float logit = logits[vocab_idx];
        if (logit > max_logit) {
            max_logit = logit;
            max_token = vocab_idx;
        }
    }

    return max_token;
}

int OcrInference::InferTokens(const float* image_data, int* out_tokens, int max_tokens) {
    if (!initialized_) {
        LOGE("OcrInference not initialized");
        return 0;
    }

    try {
        // Run encoder
        const size_t image_data_size = IMAGE_SIZE * IMAGE_SIZE * 3;

        auto write_result = litert_->encoder_input_buffers[0].Write<float>(
            absl::MakeConstSpan(image_data, image_data_size)
        );

        if (!write_result.HasValue()) {
            LOGE("Failed to write encoder input");
            return 0;
        }

        auto encoder_run_start = std::chrono::steady_clock::now();
        LOGI("About to run encoder...");
        auto encoder_run_result = litert_->compiled_encoder->Run(
            litert_->encoder_input_buffers,
            litert_->encoder_output_buffers
        );
        LOGI("Encoder run finished.");
        if (!encoder_run_result.HasValue()) {
            LOGE("Failed to run encoder: %s", encoder_run_result.Error().Message().c_str());
            return 0;
        }

        // Read encoder hidden states
        auto read_result = litert_->encoder_output_buffers[0].Read<float>(
            absl::MakeSpan(litert_->encoder_hidden_states)
        );
        if (!read_result.HasValue()) {
            LOGE("Failed to read encoder output");
            return 0;
        }

        auto encoder_run_end = std::chrono::steady_clock::now();
        const auto encoder_run_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            encoder_run_end - encoder_run_start
        ).count();
        LOGI("[PERF] Encoder runtime took %lld ms (%s)", static_cast<long long>(encoder_run_ms),
             litert_->encoder_using_gpu ? "GPU" : "CPU");

        // Initialize decoder state
        std::fill(embeddings_input_.begin(), embeddings_input_.end(), 0.0f);
        std::fill(attention_mask_.begin(), attention_mask_.end(), 0.0f);

        out_tokens[0] = START_TOKEN_ID;
        UpdateEmbedding(START_TOKEN_ID, 0);
        attention_mask_[0] = 1.0f;
        int token_count = 1;
        long long decoder_run_ms = 0;
        int decoder_iterations = 0;

        auto write_hidden_result = litert_->decoder_input_buffers[0].Write<float>(
            absl::MakeConstSpan(litert_->encoder_hidden_states)
        );
        if (!write_hidden_result.HasValue()) {
            LOGE("Failed to write decoder hidden states input");
            return 0;
        }

        for (int step = 0; step < MAX_SEQUENCE_LENGTH - 1; ++step) {
            auto write_mask_result = litert_->decoder_input_buffers[1].Write<float>(
                absl::MakeConstSpan(attention_mask_.data(), MAX_SEQUENCE_LENGTH)
            );
            if (!write_mask_result.HasValue()) {
                LOGE("Failed to write decoder attention mask input");
                break;
            }

            auto write_emb_result = litert_->decoder_input_buffers[2].Write<float>(
                absl::MakeConstSpan(embeddings_input_.data(), MAX_SEQUENCE_LENGTH * HIDDEN_SIZE)
            );
            if (!write_emb_result.HasValue()) {
                LOGE("Failed to write decoder embeddings input");
                break;
            }

            auto decoder_run_start = std::chrono::steady_clock::now();
            auto decoder_run_result = litert_->compiled_decoder->Run(
                litert_->decoder_input_buffers,
                litert_->decoder_output_buffers
            );
            if (!decoder_run_result.HasValue()) {
                LOGE("Failed to run decoder at step %d: %s", step, decoder_run_result.Error().Message().c_str());
                break;
            }
            decoder_iterations++;

            auto logits_result = litert_->decoder_output_buffers[0].Read<float>(
                absl::MakeSpan(litert_->decoder_logits)
            );
            auto decoder_run_end = std::chrono::steady_clock::now();
            decoder_run_ms += std::chrono::duration_cast<std::chrono::milliseconds>(
                decoder_run_end - decoder_run_start
            ).count();

            if (!logits_result.HasValue()) {
                LOGE("Failed to read decoder output at step %d", step);
                break;
            }

            const int next_token = FindMaxLogitToken(token_count);

            if (next_token < 0 || next_token == END_TOKEN_ID) {
                break;
            }
            out_tokens[token_count] = next_token;
            UpdateEmbedding(next_token, token_count);
            attention_mask_[token_count] = 1.0f;

            token_count++;
            if (token_count >= max_tokens || token_count >= MAX_SEQUENCE_LENGTH) {
                break;
            }
        }

        LOGI("[PERF] Decoder runtime: %lld ms across %d steps (%s)",
             static_cast<long long>(decoder_run_ms), decoder_iterations,
             litert_->decoder_using_gpu ? "GPU" : "CPU");

        const long long total_inference_ms = encoder_run_ms + decoder_run_ms;
        LOGI("[PERF] Total inference runtime: %lld ms", total_inference_ms);

        return token_count;
    } catch (const std::exception& e) {
        LOGE("Exception during inference: %s", e.what());
        return 0;
    }
}

void OcrInference::Close() {
    const auto close_start = std::chrono::steady_clock::now();

    if (litert_) {
        litert_->encoder_input_buffers.clear();
        litert_->encoder_output_buffers.clear();
        litert_->decoder_input_buffers.clear();
        litert_->decoder_output_buffers.clear();

        litert_->compiled_encoder.reset();
        litert_->compiled_decoder.reset();

        // Global g_persist_env is not destroyed
        // This ensures the GPU/OpenCL context remains valid for re-initialization.

        // Allow GPU resources to be released before continuing
        if (litert_->using_gpu) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }

    litert_.reset();

    // Close model assets
    if (encoder_asset_) {
        AAsset_close(encoder_asset_);
        encoder_asset_ = nullptr;
    }
    if (decoder_asset_) {
        AAsset_close(decoder_asset_);
        decoder_asset_ = nullptr;
    }
    if (embeddings_asset_) {
        AAsset_close(embeddings_asset_);
        embeddings_asset_ = nullptr;
    }

    // Clear and release memory back to OS
    attention_mask_.clear();
    attention_mask_.shrink_to_fit();

    if (initialized_) {
        initialized_ = false;
        LogDurationMs("OcrInference Close (Models Freed, Env Preserved)", close_start);
    }
}

} // namespace mihon
