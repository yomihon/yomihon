#ifndef YOMIHON_OCR_INFERENCE_H
#define YOMIHON_OCR_INFERENCE_H

#include <vector>
#include <string>
#include <memory>
#include <cstdint>
#include <android/asset_manager.h>

namespace mihon {

class OcrInference {
public:
    OcrInference();
    ~OcrInference();

    // Initialize with model data from memory buffers
    // Returns true on success, false on failure
    bool Initialize(
        AAsset* encoder_asset,
        AAsset* decoder_asset,
        AAsset* embeddings_asset,
        const char* cache_dir,
        const char* native_lib_dir
    );

    // Main inference method
    // Takes preprocessed image data (224x224x3 float array)
    // Returns the number of tokens generated, fills outTokens array
    int InferTokens(const float* image_data, int* out_tokens, int max_tokens);

    // Cleanup resources
    void Close();

    bool IsInitialized() const { return initialized_; }

    // Per-model GPU status checks
    bool IsEncoderUsingGpu() const;
    bool IsDecoderUsingGpu() const;

private:
    // Model constants
    static constexpr int IMAGE_SIZE = 224;
    static constexpr int MAX_SEQUENCE_LENGTH = 300;
    static constexpr int VOCAB_SIZE = 6144;
    static constexpr int HIDDEN_SIZE = 768;
    static constexpr int START_TOKEN_ID = 2;
    static constexpr int END_TOKEN_ID = 3;
    static constexpr int PAD_TOKEN_ID = 0;

    // Opaque pointers to LiteRT objects (forward declared to avoid exposing LiteRT headers)
    struct LiteRtObjects;
    std::unique_ptr<LiteRtObjects> litert_;

    AAsset* encoder_asset_ = nullptr;
    AAsset* decoder_asset_ = nullptr;
    // Embeddings and working memory
    AAsset* embeddings_asset_ = nullptr;
    const float* embeddings_data_ = nullptr;
    size_t embedding_count_ = 0;
    std::vector<float> embeddings_input_;
    std::vector<float> attention_mask_;

    bool initialized_ = false;

    // Helper methods
    void UpdateEmbedding(int token_id, int index) noexcept;
    int FindMaxLogitToken(int seq_len) const noexcept;
    bool TryCompileWithGpu(const uint8_t* encoder_data, size_t encoder_size, const uint8_t* decoder_data, size_t decoder_size);
    bool TryCompileWithCpu(const uint8_t* encoder_data, size_t encoder_size, const uint8_t* decoder_data, size_t decoder_size);
    bool PerformWarmup();
    bool CreateBuffers();
    static int GetOptimalThreadCount() noexcept;

    // Cached sizes from actual model outputs (determined during buffer creation)
    size_t encoder_output_size_ = 0;
    size_t decoder_output_size_ = 0;
};

} // namespace mihon

#endif // YOMIHON_OCR_INFERENCE_H
