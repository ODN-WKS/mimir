// Standalone mode's model runner without JNI: the phone library (standalone_jni.cpp) and the
// desktop check (desktop_check.cpp) both use it.
#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

struct Runner;

// A picture for the model: width, height and RGB bytes (3 per pixel, rows top to bottom).
struct Picture {
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<unsigned char> rgb;
};

// Where a picture goes in the prompt (llama.cpp's mtmd marker); one per picture, in order.
extern const char * const PICTURE_MARKER;

Runner * runner_load(const std::string & path, int n_ctx, int n_threads, float temp);
void runner_free(Runner * r);
void runner_stop(Runner * r);

// Loads the model's vision part (its mmproj file) once; later calls with the same file do nothing.
bool runner_vision(Runner * r, const std::string & mmproj_path, int n_threads);

// Generates a reply to `prompt` (the whole conversation, formatted, with PICTURE_MARKER where each
// picture goes). Calls on_piece with each whole-character piece; it returning false stops.
// Returns a JSON line with the counts and times, or {"error": "..."}.
std::string runner_generate(Runner * r, const std::string & prompt, const std::vector<Picture> & pictures, int max_tokens,
                            const std::function<bool(const std::string &)> & on_piece);
