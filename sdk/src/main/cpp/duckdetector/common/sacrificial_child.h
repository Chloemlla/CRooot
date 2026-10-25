/*
 * Copyright 2026 CRooot Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef DUCKDETECTOR_COMMON_SACRIFICIAL_CHILD_H
#define DUCKDETECTOR_COMMON_SACRIFICIAL_CHILD_H

#include <cerrno>
#include <cstddef>
#include <signal.h>
#include <string>
#include <sys/wait.h>
#include <unistd.h>

namespace duckdetector::sacrificial_child {

    // Reserved for these fork-only probes, not Android service exit codes.
    constexpr int kRestrictedExitCode = 190;
    constexpr int kSetupUnavailableExitCode = 191;
    constexpr int kPayloadFailedExitCode = 192;

    enum class Outcome { kCompleted, kRestricted, kSetupUnavailable, kFailed };

    inline void exit_on_sigsys(int) noexcept {
        // _exit is async-signal-safe: no debuggerd, logging, allocation or C++ unwinding.
        _exit(kRestrictedExitCode);
    }

    // Call ONLY in the fork child, before any probe. Never change the host disposition.
    // Failure means the caller must _exit(kSetupUnavailableExitCode), not run the probe.
    inline bool prepare_sigsys_exit() noexcept {
        struct sigaction action{};
        action.sa_handler = exit_on_sigsys;
        if (sigemptyset(&action.sa_mask) != 0 || sigaction(SIGSYS, &action, nullptr) != 0) {
            return false;
        }
        // The fork thread may have inherited a blocked SIGSYS. Install before unblocking.
        sigset_t signals;
        return sigemptyset(&signals) == 0 && sigaddset(&signals, SIGSYS) == 0 &&
               sigprocmask(SIG_UNBLOCK, &signals, nullptr) == 0;
    }

    inline Outcome classify_status(int status) noexcept {
        if (WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS) {
            return Outcome::kRestricted;  // Also accept the old/default signal death path.
        }
        if (!WIFEXITED(status)) return Outcome::kFailed;
        switch (WEXITSTATUS(status)) {
            case 0: return Outcome::kCompleted;
            case kRestrictedExitCode: return Outcome::kRestricted;
            case kSetupUnavailableExitCode: return Outcome::kSetupUnavailable;
            default: return Outcome::kFailed;
        }
    }

    inline bool wait_for_child(pid_t child, int &status) noexcept {
        pid_t waited;
        do {
            waited = waitpid(child, &status, 0);
        } while (waited < 0 && errno == EINTR);
        return waited == child;
    }

    inline bool write_all(int fd, const void *payload, std::size_t size) noexcept {
        const auto *bytes = static_cast<const char *>(payload);
        while (size > 0) {
            const ssize_t written = write(fd, bytes, size);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) return false;
            bytes += written;
            size -= static_cast<std::size_t>(written);
        }
        return true;
    }

    inline bool read_exact(int fd, void *payload, std::size_t size) noexcept {
        auto *bytes = static_cast<char *>(payload);
        while (size > 0) {
            const ssize_t count = read(fd, bytes, size);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return false;
            bytes += count;
            size -= static_cast<std::size_t>(count);
        }
        return true;
    }

    // Drain BEFORE wait_for_child: the serialized pack can exceed the pipe capacity.
    inline bool read_to_end(int fd, std::string &payload) {
        char buffer[1024];
        for (;;) {
            const ssize_t count = read(fd, buffer, sizeof(buffer));
            if (count < 0 && errno == EINTR) continue;
            if (count < 0) return false;
            if (count == 0) return true;
            payload.append(buffer, static_cast<std::size_t>(count));
        }
    }

}  // namespace duckdetector::sacrificial_child

#endif  // DUCKDETECTOR_COMMON_SACRIFICIAL_CHILD_H
