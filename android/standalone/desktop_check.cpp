// A desktop run of the phone's model runner (runner.cpp), for checks on the PC:
//   desktop_check <model.gguf> <mmproj.gguf|-> <picture.rgb WxH|-> <prompt file>
// The prompt file holds the formatted conversation, with the picture marker where the picture goes.
// Prints the reply, then the runner's JSON line.

#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

#include "runner.h"

int main(int argc, char ** argv) {
    if (argc < 6) {
        std::fprintf(stderr, "usage: %s model mmproj|- picture.rgb|- WxH|- prompt-file [prompt-file-2]\n", argv[0]);
        return 2;
    }
    Runner * r = runner_load(argv[1], 8192, 8, 0.2f);
    if (!r) return 1;
    std::vector<Picture> pictures;
    if (std::string(argv[2]) != "-") {
        if (!runner_vision(r, argv[2], 8)) return 1;
        Picture p;
        std::sscanf(argv[4], "%ux%u", &p.width, &p.height);
        std::ifstream f(argv[3], std::ios::binary);
        p.rgb.assign(std::istreambuf_iterator<char>(f), {});
        pictures.push_back(std::move(p));
    }
    // Each prompt file is one turn: the second one checks the context (and picture) is kept.
    for (int i = 5; i < argc; ++i) {
        std::ifstream pf(argv[i]);
        std::stringstream ss;
        ss << pf.rdbuf();
        std::string prompt = ss.str();
        while (!prompt.empty() && prompt.back() == '\n' && prompt.size() > 1 && prompt[prompt.size() - 2] == '\n') prompt.pop_back();
        const std::string json = runner_generate(r, prompt, pictures, 200, [](const std::string & piece) {
            std::cout << piece << std::flush;
            return true;
        });
        std::cout << "\n" << json << std::endl;
    }
    runner_free(r);
    return 0;
}
