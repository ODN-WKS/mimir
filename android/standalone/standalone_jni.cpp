// Standalone mode: runs a downloaded model on the phone with llama.cpp. The app calls this
// through io.github.odnwks.mimir.standalone.Llm, always off the main thread. The work
// itself is in runner.cpp.

#include <jni.h>

#include <string>
#include <vector>

#include "runner.h"

namespace {

// Text crosses as UTF-8 bytes: JNI's own string calls use a modified UTF-8 that breaks emoji.
std::string bytes_in(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray bytes_out(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_odnwks_mimir_standalone_Llm_load(JNIEnv * env, jobject, jbyteArray jpath, jint n_ctx,
                                                             jint n_threads, jfloat temp) {
    return reinterpret_cast<jlong>(runner_load(bytes_in(env, jpath), n_ctx, n_threads, temp));
}

JNIEXPORT void JNICALL
Java_io_github_odnwks_mimir_standalone_Llm_free(JNIEnv *, jobject, jlong handle) {
    runner_free(reinterpret_cast<Runner *>(handle));
}

JNIEXPORT void JNICALL
Java_io_github_odnwks_mimir_standalone_Llm_stop(JNIEnv *, jobject, jlong handle) {
    runner_stop(reinterpret_cast<Runner *>(handle));
}

JNIEXPORT jboolean JNICALL
Java_io_github_odnwks_mimir_standalone_Llm_vision(JNIEnv * env, jobject, jlong handle, jbyteArray jpath,
                                                               jint n_threads) {
    auto * r = reinterpret_cast<Runner *>(handle);
    return r && runner_vision(r, bytes_in(env, jpath), n_threads) ? JNI_TRUE : JNI_FALSE;
}

// Generates a reply to `prompt` (the whole conversation, formatted, with a marker where each
// picture goes). `pictures` holds each picture's RGB bytes, `sizes` their widths and heights in
// turn. Calls sink.onPiece(bytes) with each whole-character piece; onPiece returning false stops.
// Returns a JSON line with the counts and times, or {"error": "..."}.
JNIEXPORT jstring JNICALL
Java_io_github_odnwks_mimir_standalone_Llm_generate(JNIEnv * env, jobject, jlong handle,
                                                                 jbyteArray jprompt, jobjectArray jpictures, jintArray jsizes,
                                                                 jint max_tokens, jobject sink) {
    auto * r = reinterpret_cast<Runner *>(handle);
    const std::string prompt = bytes_in(env, jprompt);
    std::vector<Picture> pictures;
    const jsize n = jpictures ? env->GetArrayLength(jpictures) : 0;
    std::vector<jint> sizes(static_cast<size_t>(n) * 2);
    if (n > 0) env->GetIntArrayRegion(jsizes, 0, n * 2, sizes.data());
    for (jsize i = 0; i < n; ++i) {
        auto a = static_cast<jbyteArray>(env->GetObjectArrayElement(jpictures, i));
        Picture p;
        p.width = static_cast<uint32_t>(sizes[i * 2]);
        p.height = static_cast<uint32_t>(sizes[i * 2 + 1]);
        const jsize len = env->GetArrayLength(a);
        if (static_cast<size_t>(len) != static_cast<size_t>(p.width) * p.height * 3) return env->NewStringUTF("{\"error\":\"picture\"}");
        p.rgb.resize(static_cast<size_t>(len));
        env->GetByteArrayRegion(a, 0, len, reinterpret_cast<jbyte *>(p.rgb.data()));
        env->DeleteLocalRef(a);
        pictures.push_back(std::move(p));
    }

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(sink_class, "onPiece", "([B)Z");
    const std::string out = runner_generate(r, prompt, pictures, max_tokens, [&](const std::string & piece) {
        jbyteArray bytes = bytes_out(env, piece);
        const jboolean go_on = env->CallBooleanMethod(sink, on_piece, bytes);
        env->DeleteLocalRef(bytes);
        return !env->ExceptionCheck() && go_on;
    });
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"
