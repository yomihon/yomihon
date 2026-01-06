cmake_minimum_required(VERSION 3.22.1)

project("yomihon_ocr")

# Set C++ standard
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LITERT_VERSION)
    message(FATAL_ERROR "LITERT_VERSION is not defined")
endif()

# Find required Android packages
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

include(FetchContent)

# Fetch Abseil (required for LiteRT C++ API)
FetchContent_Declare(
    absl
    GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
    GIT_TAG 20240722.0
)
set(ABSL_PROPAGATE_CXX_STD ON)
set(BUILD_TESTING OFF)
FetchContent_MakeAvailable(absl)

# Fetch FlatBuffers (required by LiteRT C++ API)
FetchContent_Declare(
    flatbuffers
    GIT_REPOSITORY https://github.com/google/flatbuffers.git
    GIT_TAG v24.3.25
)
set(FLATBUFFERS_BUILD_TESTS OFF)
set(FLATBUFFERS_INSTALL OFF)
FetchContent_MakeAvailable(flatbuffers)

# Fetch Pre-built LiteRT Next
FetchContent_Declare(
    litert_prebuilt
    URL "https://github.com/yomihon/litert-cpp-dist/releases/download/${LITERT_VERSION}/litert_android.zip"
)
FetchContent_Populate(litert_prebuilt)

# Define where the extracted LiteRT files are
set(LITERT_DIST_DIR "${litert_prebuilt_SOURCE_DIR}")

# Set the dir to the same as the ABI being built
set(LITERT_ABI_LIB_DIR "${LITERT_DIST_DIR}/lib/${ANDROID_ABI}")

# Ensure the ABI requested by Gradle actually exists in the package
if(NOT EXISTS "${LITERT_ABI_LIB_DIR}")
    message(FATAL_ERROR "LiteRT library not found for ABI: ${ANDROID_ABI}")
endif()

# Create LiteRT C API imported target (the shared library)
add_library(litert_c_api SHARED IMPORTED GLOBAL)

# Point to the ABI-specific path
set_target_properties(litert_c_api PROPERTIES
    IMPORTED_LOCATION "${LITERT_ABI_LIB_DIR}/libLiteRt.so"
    INTERFACE_INCLUDE_DIRECTORIES "${LITERT_DIST_DIR}/include"
    IMPORTED_NO_SONAME TRUE
)

# Collect C++ source files from litert/cc/
file(GLOB_RECURSE LITERT_CC_SOURCES
    "${LITERT_DIST_DIR}/include/litert/cc/*.cc"
)

list(APPEND LITERT_ALL_SOURCES ${LITERT_CC_SOURCES})
list(APPEND LITERT_ALL_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/litert_logger_stub.cpp")
list(FILTER LITERT_ALL_SOURCES EXCLUDE REGEX ".*darwinn.*")
list(FILTER LITERT_ALL_SOURCES EXCLUDE REGEX ".*webnn.*")
list(FILTER LITERT_ALL_SOURCES EXCLUDE REGEX ".*_win\\.cc")
list(FILTER LITERT_ALL_SOURCES EXCLUDE REGEX ".*empty\\.cc")

# Create C++ wrapper static library
add_library(litert_cc_api STATIC ${LITERT_ALL_SOURCES})
target_include_directories(litert_cc_api PUBLIC "${LITERT_DIST_DIR}/include")
target_compile_options(litert_cc_api PRIVATE
    -Wno-error=deprecated-declarations
    -Wno-deprecated-declarations
)
target_link_libraries(litert_cc_api PUBLIC
    litert_c_api
    absl::status
    absl::statusor
    absl::strings
    absl::span
    absl::log
    absl::check
    absl::hash
    absl::flat_hash_map
    absl::cleanup
    absl::str_format
    absl::synchronization
    flatbuffers
)

# Build Project
add_library(yomihon_ocr SHARED
    ocr_native.cpp
    ocr_inference.cpp
    text_postprocessor.cpp
    vocab_data.cpp
)

target_link_libraries(yomihon_ocr
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    litert_cc_api
)

target_link_options(yomihon_ocr PRIVATE "-Wl,-z,max-page-size=16384")

# Check if the GPU library exists for this ABI before trying to copy it
# (armeabi-v7a and x86 does not yet have the OpenCL accelerator)
set(GPU_LIB_SOURCE "${LITERT_ABI_LIB_DIR}/libLiteRtOpenClAccelerator.so")

if(EXISTS "${GPU_LIB_SOURCE}")
    add_custom_command(TARGET yomihon_ocr POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${GPU_LIB_SOURCE}"
        "$<TARGET_FILE_DIR:yomihon_ocr>/libLiteRtOpenClAccelerator.so"
        COMMENT "Copying GPU accelerator library for runtime loading"
    )
endif()

add_custom_command(TARGET yomihon_ocr POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${LITERT_ABI_LIB_DIR}/libLiteRt.so"
    "$<TARGET_FILE_DIR:yomihon_ocr>/libLiteRt.so"
    COMMENT "Copying core LiteRT library for runtime loading"
)
