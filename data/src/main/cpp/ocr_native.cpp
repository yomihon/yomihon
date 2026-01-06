#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <mutex>
#include "text_postprocessor.h"
#include "vocab_data.h"
#include "ocr_inference.h"

#define LOG_TAG "Yomihon_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_inferenceMutex;

// Constants matching Kotlin implementation
static constexpr int IMAGE_SIZE = 224;
static constexpr float NORMALIZATION_FACTOR = 1.0f / (255.0f * 0.5f);
static constexpr float NORMALIZED_MEAN = 0.5f / 0.5f;
static constexpr int SPECIAL_TOKEN_THRESHOLD = 5;
static constexpr int MAX_SEQUENCE_LENGTH = 300;

// Global instances
static std::unique_ptr<mihon::TextPostprocessor> g_textPostprocessor;
static std::vector<std::string> g_vocab;
static std::unique_ptr<mihon::OcrInference> g_ocrInference;
static std::mutex g_initMutex;
static std::atomic<int> g_activeOcrClients{0};

// Pre-allocated buffers for inference (avoid allocation per call)
static std::vector<float> g_imageBuffer;
static std::vector<int> g_tokenBuffer;

static void PreprocessBitmap(JNIEnv* env, jobject bitmap, float* output) {
    AndroidBitmapInfo info;
    void* pixels;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return;
    }

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return;
    }

    try {
        auto* srcPixels = static_cast<uint32_t*>(pixels);
        int outIndex = 0;

        for (int i = 0; i < IMAGE_SIZE * IMAGE_SIZE; i++) {
            uint32_t pixel = srcPixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            output[outIndex++] = r * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
            output[outIndex++] = g * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
            output[outIndex++] = b * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
        }

    } catch (const std::exception& e) {
        LOGE("Exception during preprocessing: %s", e.what());
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_mihon_data_ocr_OcrRepositoryImpl_nativeOcrInit(
    JNIEnv* env,
    jobject /* this */,
    jobject assetManager,
    jstring cacheDir,
    jstring nativeLibDir) {

    LOGI("Initializing native OCR engine");

    std::lock_guard<std::mutex> lock(g_initMutex);

    try {
        if (g_ocrInference && g_ocrInference->IsInitialized()) {
            const int clients = g_activeOcrClients.fetch_add(1) + 1;
            LOGI("Reusing existing native OCR engine (clients=%d, ACCELERATOR=%s/%s)", clients,
                 g_ocrInference->IsEncoderUsingGpu() ? "GPU" : "CPU",
                 g_ocrInference->IsDecoderUsingGpu() ? "GPU" : "CPU");
            return JNI_TRUE;
        }

        g_textPostprocessor = std::make_unique<mihon::TextPostprocessor>();
        g_vocab = mihon::getVocabulary();

        AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
        if (!mgr) {
            LOGE("Failed to get AAssetManager");
            return JNI_FALSE;
        }

        // Zero-copy asset loading
        AAsset* enc_asset = AAssetManager_open(mgr, "ocr/encoder.tflite", AASSET_MODE_BUFFER);
        AAsset* dec_asset = AAssetManager_open(mgr, "ocr/decoder.tflite", AASSET_MODE_BUFFER);
        AAsset* emb_asset = AAssetManager_open(mgr, "ocr/embeddings.bin", AASSET_MODE_BUFFER);

        if (!enc_asset || !dec_asset || !emb_asset) {
            LOGE("Failed to open assets");
            if (enc_asset) AAsset_close(enc_asset);
            if (dec_asset) AAsset_close(dec_asset);
            if (emb_asset) AAsset_close(emb_asset);
            return JNI_FALSE;
        }

        const char* cache_dir_str = env->GetStringUTFChars(cacheDir, nullptr);
        const char* native_lib_dir_str = env->GetStringUTFChars(nativeLibDir, nullptr);

        // Create OcrInference instance (assets are now owned by OcrInference)
        g_ocrInference = std::make_unique<mihon::OcrInference>();
        bool success = g_ocrInference->Initialize(
            enc_asset,
            dec_asset,
            emb_asset,
            cache_dir_str,
            native_lib_dir_str
        );

        env->ReleaseStringUTFChars(cacheDir, cache_dir_str);
        env->ReleaseStringUTFChars(nativeLibDir, native_lib_dir_str);

        if (!success) {
            LOGE("Failed to initialize OcrInference");
            g_ocrInference.reset();
            g_activeOcrClients.store(0);
            return JNI_FALSE;
        }
        g_activeOcrClients.store(1);

        g_imageBuffer.resize(IMAGE_SIZE * IMAGE_SIZE * 3);
        g_tokenBuffer.resize(MAX_SEQUENCE_LENGTH);

        LOGI("app.yomihon: Native OCR engine initialized successfully (ACCELERATOR=%s/%s)",
             g_ocrInference->IsEncoderUsingGpu() ? "GPU" : "CPU",
             g_ocrInference->IsDecoderUsingGpu() ? "GPU" : "CPU");
        return JNI_TRUE;

    } catch (const std::exception& e) {
        LOGE("Exception during OCR initialization: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_mihon_data_ocr_OcrRepositoryImpl_nativeRecognizeText(
    JNIEnv* env,
    jobject /* this */,
    jobject bitmap) {

    std::lock_guard<std::mutex> lock(g_inferenceMutex);

    if (!g_ocrInference || !g_ocrInference->IsInitialized()) {
        LOGE("OcrInference not initialized");
        return env->NewStringUTF("");
    }

 try {
        float* image_data = g_imageBuffer.data();
        int* tokens = g_tokenBuffer.data();

        PreprocessBitmap(env, bitmap, image_data);

        auto t0 = std::chrono::high_resolution_clock::now();
        const int token_count = g_ocrInference->InferTokens(
            image_data,
            tokens,
            MAX_SEQUENCE_LENGTH
        );
        auto t1 = std::chrono::high_resolution_clock::now();
        auto diff = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        LOGI("app.yomihon: Native inference overall time: %lld ms", static_cast<long long>(diff));

        if (token_count <= 0) {
            LOGE("Inference failed or produced no tokens");
            return env->NewStringUTF("");
        }

        std::string result;
        result.reserve(static_cast<size_t>(token_count) * 3);

        const int vocab_size = static_cast<int>(g_vocab.size());
        for (int i = 0; i < token_count; ++i) {
            const int tokenId = tokens[i];

            if (tokenId < SPECIAL_TOKEN_THRESHOLD) {
                continue;
            }

            if (tokenId < vocab_size) {
                result += g_vocab[tokenId];
            }
        }

        if (g_textPostprocessor) {
            result = g_textPostprocessor->postprocess(result);
        }

        return env->NewStringUTF(result.c_str());

    } catch (const std::exception& e) {
        LOGE("Exception during recognition: %s", e.what());
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_mihon_data_ocr_OcrRepositoryImpl_nativeOcrClose(JNIEnv* env, jobject /* this */) {
    LOGI("Closing native OCR engine");

    std::lock_guard<std::mutex> lock(g_initMutex);

    int previous_clients = g_activeOcrClients.load();
    if (previous_clients > 1) {
        int remaining = g_activeOcrClients.fetch_sub(1) - 1;
        LOGI("nativeOcrClose: deferring shutdown, %d client(s) still active", remaining);
        return;
    }
    g_activeOcrClients.store(0);

    if (g_ocrInference) {
        g_ocrInference->Close();
        g_ocrInference.reset();
    }
    g_textPostprocessor.reset();
    g_vocab.clear();

    g_imageBuffer.clear();
    g_imageBuffer.shrink_to_fit();
    g_tokenBuffer.clear();
    g_tokenBuffer.shrink_to_fit();

    LOGI("Native OCR engine closed");
}

} // extern "C"
