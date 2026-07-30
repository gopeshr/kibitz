// JNI bridge to Stockfish.
//
// Stockfish talks UCI over stdin/stdout. Rather than reimplement its engine API, this
// redirects the process's stdin and stdout onto pipes and runs Stockfish's normal UCI loop
// on a background thread. The Kotlin side then speaks plain UCI through those pipes, which
// means the entire protocol surface Stockfish supports is available with no extra binding
// code to keep in sync as Stockfish evolves.
//
// Stockfish is GPLv3; see cpp/stockfish/Copying.txt. Distributing this app therefore carries
// GPLv3 obligations for the engine component.

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <string>
#include <thread>
#include <unistd.h>

#include "stockfish/bitboard.h"
#include "stockfish/misc.h"
#include "stockfish/position.h"
#include "stockfish/tune.h"
#include "stockfish/uci.h"

namespace {

// toEngine: Kotlin writes commands, Stockfish reads them as stdin.
// fromEngine: Stockfish writes replies as stdout, Kotlin reads them.
int toEngine[2] = {-1, -1};
int fromEngine[2] = {-1, -1};

std::atomic<bool> started{false};

// Reassembles whole lines from the pipe, since a read can land mid-line.
std::string pending;

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_gopesh_kibitz_engine_stockfish_Stockfish_nativeStart(JNIEnv* env,
                                                          jobject /*thiz*/,
                                                          jstring workingDirectory) {
    if (started.exchange(true)) return JNI_TRUE;  // already running

    // Stockfish resolves default network names relative to the working directory, so
    // pointing the process at the directory holding the extracted .nnue files is enough
    // for it to find them without any setoption dance.
    const char* dir = env->GetStringUTFChars(workingDirectory, nullptr);
    if (dir != nullptr) {
        chdir(dir);
        env->ReleaseStringUTFChars(workingDirectory, dir);
    }

    if (pipe(toEngine) != 0 || pipe(fromEngine) != 0) {
        started = false;
        return JNI_FALSE;
    }
    if (dup2(toEngine[0], STDIN_FILENO) < 0 || dup2(fromEngine[1], STDOUT_FILENO) < 0) {
        started = false;
        return JNI_FALSE;
    }
    // Line buffering, or replies would sit in the C library's buffer until it filled.
    setvbuf(stdout, nullptr, _IOLBF, 4096);

    std::thread([] {
        Stockfish::Bitboards::init();
        Stockfish::Position::init();

        // argv[0] would normally be the binary path; Stockfish only uses it to guess where
        // to look for networks, and chdir above already answers that.
        char arg0[] = "stockfish";
        char* argv[] = {arg0, nullptr};

        auto uci = std::make_unique<Stockfish::UCIEngine>(1, argv);
        Stockfish::Tune::init(uci->engine_options());
        uci->loop();
    }).detach();

    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_gopesh_kibitz_engine_stockfish_Stockfish_nativeWrite(
    JNIEnv* env, jobject /*thiz*/, jstring command) {
    if (!started.load()) return;
    const char* text = env->GetStringUTFChars(command, nullptr);
    if (text == nullptr) return;
    std::string line(text);
    env->ReleaseStringUTFChars(command, text);
    line.push_back('\n');
    ssize_t ignored = write(toEngine[1], line.c_str(), line.size());
    (void) ignored;
}

/**
 * Blocks until Stockfish emits a complete line, then returns it without the newline.
 * Returns null if the pipe closes.
 */
JNIEXPORT jstring JNICALL
Java_gopesh_kibitz_engine_stockfish_Stockfish_nativeReadLine(JNIEnv* env, jobject /*thiz*/) {
    if (!started.load()) return nullptr;

    for (;;) {
        const auto newline = pending.find('\n');
        if (newline != std::string::npos) {
            std::string line = pending.substr(0, newline);
            pending.erase(0, newline + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            return env->NewStringUTF(line.c_str());
        }

        char buffer[4096];
        const ssize_t count = read(fromEngine[0], buffer, sizeof(buffer));
        if (count <= 0) return nullptr;
        pending.append(buffer, static_cast<size_t>(count));
    }
}

}  // extern "C"
