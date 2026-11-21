// One loaded model is a Runner: the model, one context and one sampler, and once a picture is
// sent, the model's vision part. Each turn the app sends the whole conversation; the part already
// in the context (the shared beginning, pictures included) is kept, so a reply only costs what's new.

#include "runner.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <thread>

#ifdef __linux__
#include <sched.h>
#endif

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "MimirStandalone"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW_TEXT(text) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", text)
#else
#define LOGI(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define LOGW_TEXT(text) std::fputs(text, stderr)
#endif

const char * const PICTURE_MARKER = "<__media__>";

namespace {

// One step of what the context holds: a text token, or a whole picture (by its id).
struct Entry {
    llama_token token = -1;
    std::string picture;
    llama_pos n_pos = 1;
    bool operator==(const Entry & o) const { return token == o.token && picture == o.picture; }
};

std::once_flag backend_once;

double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// Length of the longest prefix of `s` that ends on a whole UTF-8 character.
size_t utf8_whole(const std::string & s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {  // a lead byte or plain ASCII
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

// A picture's id: the same picture in a later turn is found in the context and not encoded again.
std::string picture_id(const Picture & p) {
    uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : p.rgb) h = (h ^ c) * 1099511628211ULL;
    char out[48];
    std::snprintf(out, sizeof(out), "%ux%u-%016llx", p.width, p.height, static_cast<unsigned long long>(h));
    return out;
}

}  // namespace

struct Runner {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    llama_batch_ext * batch = nullptr;
    const llama_vocab * vocab = nullptr;
    mtmd_context * vision = nullptr;
    std::string vision_path;
    std::vector<Entry> cached;  // what the context holds, in order
    std::atomic<bool> stop{false};
    std::mutex busy;
    int threads = 1;
};

namespace {

bool decode(Runner * r, const llama_token * tokens, int n, llama_pos pos0, bool logits_last) {
    const int n_batch = static_cast<int>(llama_n_batch(r->ctx));
    for (int start = 0; start < n; start += n_batch) {
        if (r->stop.load()) return false;
        const int count = std::min(n_batch, n - start);
        llama_batch_ext_clear(r->batch);
        for (int i = 0; i < count; ++i) {
            const int32_t idx = llama_batch_ext_add_token(r->batch, 0, tokens[start + i]);
            const llama_pos pos = pos0 + start + i;
            llama_batch_ext_set_pos(r->batch, idx, &pos);
        }
        if (logits_last && start + count == n) llama_batch_ext_set_output_logits(r->batch, count - 1, true);
        const int ret = llama_process(r->ctx, LLAMA_PROCESS_TYPE_DECODE, r->batch);
        if (ret != 0) {
            LOGE("decode failed: %d", ret);
            return false;
        }
    }
    return true;
}

llama_pos pos_of(const std::vector<Entry> & e, size_t n) {
    llama_pos p = 0;
    for (size_t i = 0; i < n; ++i) p += e[i].n_pos;
    return p;
}

std::string error(const std::string & what) { return "{\"error\":\"" + what + "\"}"; }

// The cores this process may run on right now. Android moves an app in the background (and
// Samsung an app it calls "abnormal") onto 4 of the 8 cores; more threads than cores then wait
// on each other at every step, and a reply crawls at seconds a word (measured on an S25).
int cores_allowed() {
#ifdef __linux__
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) == 0) {
        const int n = CPU_COUNT(&set);
        if (n > 0) return n;
    }
#endif
    const unsigned n = std::thread::hardware_concurrency();
    return n > 0 ? static_cast<int>(n) : 1;
}

}  // namespace

Runner * runner_load(const std::string & path, int n_ctx, int n_threads, float temp) {
    std::call_once(backend_once, [] {
        llama_log_set([](enum ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_WARN) LOGW_TEXT(text);
        }, nullptr);
        mtmd_helper_log_set([](enum ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_WARN) LOGW_TEXT(text);
        }, nullptr);
        llama_backend_init();
        LOGI("%s", llama_print_system_info());
    });
    auto * r = new Runner();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    r->model = llama_model_load_from_file(path.c_str(), mp);
    if (!r->model) {
        LOGE("could not load %s", path.c_str());
        delete r;
        return nullptr;
    }
    r->vocab = llama_model_get_vocab(r->model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    r->ctx = llama_init_from_model(r->model, cp);
    if (!r->ctx) {
        LOGE("could not make a context");
        llama_model_free(r->model);
        delete r;
        return nullptr;
    }
    r->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(r->sampler, llama_sampler_init_top_k(64));
    llama_sampler_chain_add(r->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(r->sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(r->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    r->batch = llama_batch_ext_init(r->ctx);
    r->threads = n_threads;
    return r;
}

void runner_free(Runner * r) {
    if (!r) return;
    r->stop = true;
    std::lock_guard<std::mutex> lock(r->busy);
    if (r->vision) mtmd_free(r->vision);
    llama_batch_ext_free(r->batch);
    llama_sampler_free(r->sampler);
    llama_free(r->ctx);
    llama_model_free(r->model);
    delete r;
}

void runner_stop(Runner * r) {
    if (r) r->stop = true;
}

bool runner_vision(Runner * r, const std::string & mmproj_path, int n_threads) {
    std::lock_guard<std::mutex> lock(r->busy);
    if (r->vision && r->vision_path == mmproj_path) return true;
    if (r->vision) {
        mtmd_free(r->vision);
        r->vision = nullptr;
    }
    mtmd_context_params p = mtmd_context_params_default();
    p.use_gpu = false;
    p.print_timings = false;
    p.n_threads = n_threads;
    p.media_marker = PICTURE_MARKER;
    p.warmup = false;
    r->vision = mtmd_init_from_file(mmproj_path.c_str(), r->model, p);
    if (!r->vision || !mtmd_support_vision(r->vision)) {
        LOGE("could not load the vision part %s", mmproj_path.c_str());
        if (r->vision) mtmd_free(r->vision);
        r->vision = nullptr;
        return false;
    }
    r->vision_path = mmproj_path;
    return true;
}

std::string runner_generate(Runner * r, const std::string & prompt, const std::vector<Picture> & pictures, int max_tokens,
                            const std::function<bool(const std::string &)> & on_piece) {
    std::lock_guard<std::mutex> lock(r->busy);
    r->stop = false;
    // Never more threads than the cores allowed now (fewer in the background).
    const int threads = std::max(1, std::min(r->threads, cores_allowed()));
    llama_set_n_threads(r->ctx, threads, threads);

    // The prompt as entries: text tokens, and each picture as one entry (with its chunk to encode).
    std::vector<Entry> entries;
    std::vector<size_t> chunk_start;  // entry index where each chunk begins
    mtmd_input_chunks * chunks = nullptr;
    struct Free {
        mtmd_input_chunks *& c;
        ~Free() { if (c) mtmd_input_chunks_free(c); }
    } free_chunks{chunks};

    if (pictures.empty()) {
        int n = -llama_tokenize(r->vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(r->vocab, prompt.c_str(), prompt.size(), tokens.data(), n, true, true) < 0) return error("tokenize");
        for (llama_token t : tokens) entries.push_back({t, {}, 1});
    } else {
        if (!r->vision) return error("no_vision");
        std::vector<mtmd_bitmap *> bitmaps;
        for (const Picture & p : pictures) {
            mtmd_bitmap * b = mtmd_bitmap_init(p.width, p.height, p.rgb.data());
            mtmd_bitmap_set_id(b, picture_id(p).c_str());
            bitmaps.push_back(b);
        }
        chunks = mtmd_input_chunks_init();
        mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
        const int32_t ret = mtmd_tokenize(r->vision, chunks, &text, const_cast<const mtmd_bitmap **>(bitmaps.data()), bitmaps.size());
        for (mtmd_bitmap * b : bitmaps) mtmd_bitmap_free(b);
        if (ret != 0) return error(ret == 1 ? "pictures" : "picture");
        for (size_t i = 0; i < mtmd_input_chunks_size(chunks); ++i) {
            const mtmd_input_chunk * c = mtmd_input_chunks_get(chunks, i);
            chunk_start.push_back(entries.size());
            if (mtmd_input_chunk_get_type(c) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                size_t n = 0;
                const llama_token * t = mtmd_input_chunk_get_tokens_text(c, &n);
                for (size_t k = 0; k < n; ++k) entries.push_back({t[k], {}, 1});
            } else {
                const char * id = mtmd_input_chunk_get_id(c);
                entries.push_back({-1, std::string("pic:") + (id ? id : "?"), mtmd_input_chunk_get_n_pos(c)});
            }
        }
    }
    if (entries.empty()) return error("tokenize");
    const llama_pos n_prompt = pos_of(entries, entries.size());
    const int n_ctx = static_cast<int>(llama_n_ctx(r->ctx));
    if (n_prompt + max_tokens > n_ctx) {
        return "{\"error\":\"context\",\"prompt_tokens\":" + std::to_string(n_prompt) + "}";
    }

    // Keep what the context already holds, up to where this prompt first differs.
    size_t same = 0;
    while (same < r->cached.size() && same < entries.size() && r->cached[same] == entries[same]) ++same;
    // Only reading ahead (no answer wanted) and exactly this is read already: nothing to do. Going
    // back one step would cost a model whose memory can't step back (Qwen 3.5) the whole prompt.
    // (When more than this is held, e.g. the last chat, it must be cut back to this, below: the
    // answer that follows then only reads what's new.)
    if (same == entries.size() && max_tokens == 0 && same == r->cached.size()) {
        return "{\"prompt_tokens\":" + std::to_string(n_prompt) + ",\"new_prompt_tokens\":0,\"pictures\":0,\"prompt_ms\":0,\"gen_tokens\":0,\"gen_ms\":0,\"threads\":" +
               std::to_string(threads) + ",\"stopped\":false}";
    }
    if (same == entries.size()) --same;  // always process at least the last entry, for its logits
    llama_memory_t mem = llama_get_memory(r->ctx);
    if (!llama_memory_seq_rm(mem, 0, pos_of(entries, same), -1)) {
        llama_memory_clear(mem, true);
        same = 0;
    }
    r->cached.assign(entries.begin(), entries.begin() + static_cast<long>(same));

    const double t0 = now_ms();
    llama_pos n_past = pos_of(entries, same);
    bool ok = true;
    if (!chunks) {
        std::vector<llama_token> rest;
        for (size_t i = same; i < entries.size(); ++i) rest.push_back(entries[i].token);
        ok = decode(r, rest.data(), static_cast<int>(rest.size()), n_past, true);
        n_past = n_prompt;
    } else {
        const size_t n_chunks = mtmd_input_chunks_size(chunks);
        const int32_t n_batch = static_cast<int32_t>(llama_n_batch(r->ctx));
        for (size_t i = 0; ok && i < n_chunks; ++i) {
            const size_t begin = chunk_start[i];
            const size_t end = i + 1 < n_chunks ? chunk_start[i + 1] : entries.size();
            if (end <= same) continue;  // already in the context
            const bool last = i + 1 == n_chunks;
            const mtmd_input_chunk * c = mtmd_input_chunks_get(chunks, i);
            if (mtmd_input_chunk_get_type(c) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                std::vector<llama_token> rest;
                for (size_t k = std::max(begin, same); k < end; ++k) rest.push_back(entries[k].token);
                ok = decode(r, rest.data(), static_cast<int>(rest.size()), n_past, last);
                n_past += static_cast<llama_pos>(rest.size());
            } else {
                if (r->stop.load()) { ok = false; break; }
                llama_pos new_n_past = n_past;
                ok = mtmd_helper_eval_chunk_single(r->vision, r->ctx, c, n_past, 0, n_batch, last, &new_n_past) == 0;
                n_past = new_n_past;
            }
        }
    }
    if (!ok) {
        r->cached.clear();
        llama_memory_clear(mem, true);
        return error(r->stop ? "stopped" : "decode");
    }
    r->cached = entries;
    const double t1 = now_ms();

    llama_sampler_reset(r->sampler);
    std::string pending;
    int generated = 0;
    bool stopped = false;
    for (; generated < max_tokens; ++generated) {
        if (r->stop.load()) {
            stopped = true;
            break;
        }
        const llama_token tok = llama_sampler_sample(r->sampler, r->ctx, -1);
        if (llama_vocab_is_eog(r->vocab, tok)) break;
        char piece[256];
        const int len = llama_token_to_piece(r->vocab, tok, piece, sizeof(piece), 0, true);  // special: Gemma's end-of-thought mark is one
        if (len > 0) pending.append(piece, len);
        const size_t whole = utf8_whole(pending);
        if (whole > 0) {
            const bool go_on = on_piece(pending.substr(0, whole));
            pending.erase(0, whole);
            if (!go_on) {
                stopped = true;
                ++generated;
                break;
            }
        }
        if (!decode(r, &tok, 1, n_past, true)) {
            stopped = r->stop.load();
            break;
        }
        ++n_past;
        r->cached.push_back({tok, {}, 1});
    }
    const double t2 = now_ms();
    return "{\"prompt_tokens\":" + std::to_string(n_prompt) +
           ",\"new_prompt_tokens\":" + std::to_string(n_prompt - pos_of(entries, same)) +
           ",\"pictures\":" + std::to_string(pictures.size()) +
           ",\"prompt_ms\":" + std::to_string(static_cast<long>(t1 - t0)) +
           ",\"gen_tokens\":" + std::to_string(generated) +
           ",\"gen_ms\":" + std::to_string(static_cast<long>(t2 - t1)) +
           ",\"threads\":" + std::to_string(threads) +
           ",\"stopped\":" + (stopped ? "true" : "false") + "}";
}
