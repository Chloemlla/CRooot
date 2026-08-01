/*
 * Copyright 2026 Duck Apps Contributor
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

#include "nativeroot/probes/kernelpatch_nr_supercall_latency_probe.h"

#include <csignal>
#include <cstdio>
#include <cstring>
#include <string>
#include <cstdint>

#include <fcntl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

namespace duckdetector::nativeroot {

#if defined(__aarch64__)

    namespace {
        constexpr int kIterations = 100000;
        constexpr int kSupercallNr = 45;
        constexpr unsigned long kSupercallHello = 0x1000;

        static inline uint64_t get_cntfrq() {
            uint64_t val;
            asm volatile("mrs %0, cntfrq_el0" : "=r" (val));
            return val;
        }

        static inline uint64_t get_cntvct() {
            uint64_t val;
            asm volatile("isb; mrs %0, cntvct_el0; isb" : "=r" (val));
            return val;
        }

        double measure_latency(const char *buffer) {
            uint64_t start, end;
            uint64_t total_ticks = 0;
            const uint64_t freq = get_cntfrq();

            if (freq == 0) return 0.0;

            syscall(kSupercallNr, buffer, kSupercallHello);

            for (int i = 0; i < kIterations; i++) {
                start = get_cntvct();
                syscall(kSupercallNr, buffer, kSupercallHello);
                end = get_cntvct();
                
                total_ticks += (end - start);
            }

            return (static_cast<double>(total_ticks) * 1000000.0) / (static_cast<double>(freq) * kIterations);
        }

        struct LatencyResult {
            double full_latency;
            double empty_latency;
            bool success;
        };

        bool run_benchmark_in_child(LatencyResult &out_result, bool &blocked_by_seccomp) {
            int pipe_fds[2] = {-1, -1};
            if (pipe(pipe_fds) != 0) return false;

            fcntl(pipe_fds[0], F_SETFD, FD_CLOEXEC);
            fcntl(pipe_fds[1], F_SETFD, FD_CLOEXEC);

            const pid_t pid = fork();
            if (pid < 0) {
                close(pipe_fds[0]);
                close(pipe_fds[1]);
                return false;
            }

            if (pid == 0) {
                close(pipe_fds[0]);

                char buffer[128];
                LatencyResult result{};

                memset(buffer, 'A', 127);
                buffer[127] = '\0';
                result.full_latency = measure_latency(buffer);

                memset(buffer, 0, sizeof(buffer));
                result.empty_latency = measure_latency(buffer);
                
                result.success = true;

                const ssize_t ignored = write(pipe_fds[1], &result, sizeof(result));
                (void) ignored;
                close(pipe_fds[1]);
                _exit(0);
            }

            close(pipe_fds[1]);
            int status = 0;
            if (waitpid(pid, &status, 0) < 0) {
                close(pipe_fds[0]);
                return false;
            }

            if (WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS) {
                blocked_by_seccomp = true;
                close(pipe_fds[0]);
                return false;
            }

            const ssize_t bytes_read = read(pipe_fds[0], &out_result, sizeof(out_result));
            close(pipe_fds[0]);

            return WIFEXITED(status) && WEXITSTATUS(status) == 0 &&
                   bytes_read == static_cast<ssize_t>(sizeof(out_result));
        }
    } // namespace

    ProbeResult run_kernelpatch_supercall_latency_check() {
        ProbeResult result;
        bool blocked = false;
        LatencyResult latencies = {0.0, 0.0, false};

        if (!run_benchmark_in_child(latencies, blocked)) {
            if (blocked) {
                result.checked_count = 1;
                result.denied_count = 1;
                result.findings.push_back(
                    Finding{
                        .group = "SECCOMP",
                        .label = "System Call Filtered",
                        .value = "SIGSYS Received",
                        .detail = "Syscall 45 was blocked by Seccomp on ARM64.",
                        .severity = Severity::kDanger,
                    }
                );
            }
            return result;
        }

        result.checked_count = 1;
        if (!latencies.success) return result;

        double diff = latencies.full_latency - latencies.empty_latency;

        char detail[256];
        snprintf(
                detail,
                sizeof(detail),
                "Full: %.4f us, Empty: %.4f us, Diff: %.4f us",
                latencies.full_latency,
                latencies.empty_latency,
                diff
        );

        result.extra_text = detail;

        if (diff > 3.0) {
            result.flags.apatch = true;
            result.hit_count = 1;

            result.findings.push_back(
                    Finding{
                            .group = "SYSCALL",
                            .label = "KernelPatch supercall delay",
                            .value = "Detected",
                            .detail = detail,
                            .severity = Severity::kDanger,
                    }
            );
        }

        return result;
    }

#else

    ProbeResult run_kernelpatch_supercall_latency_check() {
        ProbeResult result;
        result.extra_text = "";
        return result;
    }

#endif // aarch64

} // namespace duckdetector::nativeroot
