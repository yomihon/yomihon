FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_SDK_ROOT=/opt/android-sdk

ENV ANDROID_HOME=${ANDROID_SDK_ROOT}
ENV ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT}
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

RUN apt-get update \
    && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg unzip wget \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" \
        > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends temurin-17-jdk \
    && ln -sf /usr/lib/jvm/temurin-17-jdk-$(dpkg --print-architecture) /usr/lib/jvm/temurin-17-jdk-current \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-current
ENV GRADLE_USER_HOME=/home/vscode/.gradle

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools ${ANDROID_SDK_ROOT}/platform-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/android-cmdline-tools \
    && mv /tmp/android-cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && yes | sdkmanager --sdk_root=${ANDROID_SDK_ROOT} --licenses > /dev/null \
    && sdkmanager --sdk_root=${ANDROID_SDK_ROOT} \
        "platform-tools" \
        "build-tools;35.0.1" \
        "platforms;android-35" \
        "platforms;android-36" \
        "ndk;27.1.12297006" \
    && chown -R vscode:vscode ${ANDROID_SDK_ROOT} \
    && rm -rf /tmp/android-cmdline-tools /tmp/cmdline-tools.zip
