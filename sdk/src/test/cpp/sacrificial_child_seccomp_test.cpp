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

// Linux-only CI regression. Filters are installed only in disposable test children.
#include "common/sacrificial_child.h"
#include "nativeroot/probes/ksu_supercall_probe.h"

#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/time.h>

namespace child_policy = duckdetector::sacrificial_child;
using child_policy::Outcome;

namespace {
    void require(bool condition, const char *message) {
        if (!condition) {
            std::fprintf(stderr, "FAIL: %s (errno=%d)\n", message, errno);
            std::exit(EXIT_FAILURE);
        }
    }

    template <typename Action>
    int run_child(Action action) {
        const pid_t child = fork();
        require(child >= 0, "fork test child");
        if (child == 0) {
            action();
            _exit(0);
        }
        int status = 0;
        require(child_policy::wait_for_child(child, status), "reap test child");
        return status;
    }

    void install_filter(int syscall_number, unsigned int action) {
        struct sock_filter instructions[] = {
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, static_cast<unsigned int>(syscall_number), 0, 1),
                BPF_STMT(BPF_RET | BPF_K, action),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program{static_cast<unsigned short>(sizeof(instructions) / sizeof(instructions[0])),
                                  instructions};
        require(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0, "enable no-new-privileges in test child");
        require(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0, "install real seccomp filter");
    }

    volatile sig_atomic_t parent_sigsys_count = 0;
    void parent_sigsys_handler(int, siginfo_t *, void *) {
        parent_sigsys_count = 1;
    }

    bool same_mask(const sigset_t &left, const sigset_t &right) {
        for (int signal = 1; signal < NSIG; ++signal) {
            if (sigismember(&left, signal) != sigismember(&right, signal)) return false;
        }
        return true;
    }

    void test_real_trap_preserves_parent() {
        struct sigaction original_action{};
        sigset_t original_mask;
        require(sigaction(SIGSYS, nullptr, &original_action) == 0, "save parent SIGSYS action");
        require(sigprocmask(SIG_SETMASK, nullptr, &original_mask) == 0, "save parent signal mask");
        struct sigaction sentinel{};
        sentinel.sa_sigaction = parent_sigsys_handler;
        sentinel.sa_flags = SA_SIGINFO;
        sigemptyset(&sentinel.sa_mask);
        sigaddset(&sentinel.sa_mask, SIGUSR2);
        require(sigaction(SIGSYS, &sentinel, nullptr) == 0, "set parent SIGSYS sentinel");
        sigset_t blocked;
        sigemptyset(&blocked);
        sigaddset(&blocked, SIGSYS);
        require(sigprocmask(SIG_BLOCK, &blocked, nullptr) == 0, "block SIGSYS before fork");
        struct sigaction before{};
        sigset_t mask_before;
        require(sigaction(SIGSYS, nullptr, &before) == 0, "snapshot sentinel action");
        require(sigprocmask(SIG_SETMASK, nullptr, &mask_before) == 0, "snapshot parent mask");

        const int status = run_child([&] {
            require(child_policy::prepare_sigsys_exit(), "prepare child SIGSYS exit");
            sigset_t child_mask;
            require(sigprocmask(SIG_SETMASK, nullptr, &child_mask) == 0, "query child mask");
            require(sigismember(&child_mask, SIGSYS) == 0, "unblock inherited SIGSYS in child");
            sigset_t expected = mask_before;
            sigdelset(&expected, SIGSYS);
            require(same_mask(child_mask, expected), "preserve other child mask bits");
            install_filter(SYS_getppid, SECCOMP_RET_TRAP);
            syscall(SYS_getppid);
            _exit(90);  // A real seccomp trap must never return from the handler.
        });
        require(WIFEXITED(status) && WEXITSTATUS(status) == child_policy::kRestrictedExitCode,
                "real seccomp trap must be a reserved normal exit, not signal death");
        require(child_policy::classify_status(status) == Outcome::kRestricted, "classify real restriction");
        struct sigaction after{};
        sigset_t mask_after;
        require(sigaction(SIGSYS, nullptr, &after) == 0, "query parent action after child");
        require(sigprocmask(SIG_SETMASK, nullptr, &mask_after) == 0, "query parent mask after child");
        require(before.sa_sigaction == after.sa_sigaction && before.sa_flags == after.sa_flags &&
                same_mask(before.sa_mask, after.sa_mask), "parent SIGSYS disposition must not change");
        require(same_mask(mask_before, mask_after), "parent signal mask must not change");
        require(parent_sigsys_count == 0, "child must not run inherited parent handler");
        require(sigprocmask(SIG_UNBLOCK, &blocked, nullptr) == 0, "unblock parent sentinel for smoke check");
        require(raise(SIGSYS) == 0 && parent_sigsys_count == 1, "parent sentinel still handles SIGSYS");
        require(sigaction(SIGSYS, &original_action, nullptr) == 0, "restore parent action");
        require(sigprocmask(SIG_SETMASK, &original_mask, nullptr) == 0, "restore parent mask");
    }

    void test_exit_classification() {
        const int normal = run_child([] {
            require(child_policy::prepare_sigsys_exit(), "prepare normal child");
        });
        require(child_policy::classify_status(normal) == Outcome::kCompleted, "normal exit classification");
        require(child_policy::classify_status(run_child([] { _exit(7); })) == Outcome::kFailed,
                "unexpected exit is a failure, not clean or restricted");
        require(child_policy::classify_status(run_child([] { _exit(child_policy::kPayloadFailedExitCode); })) ==
                Outcome::kFailed, "payload failure classification");
        const int unrelated = run_child([] { raise(SIGTERM); });
        require(WIFSIGNALED(unrelated) && child_policy::classify_status(unrelated) == Outcome::kFailed,
                "unrelated signal death is not a seccomp restriction");
        const int legacy = run_child([] {
            const struct rlimit no_core{0, 0};
            require(setrlimit(RLIMIT_CORE, &no_core) == 0, "disable legacy test core dumps");
            struct sigaction action{};
            action.sa_handler = SIG_DFL;
            sigemptyset(&action.sa_mask);
            require(sigaction(SIGSYS, &action, nullptr) == 0, "set legacy child default disposition");
            sigset_t signals;
            sigemptyset(&signals);
            sigaddset(&signals, SIGSYS);
            require(sigprocmask(SIG_UNBLOCK, &signals, nullptr) == 0, "unblock legacy child SIGSYS");
            raise(SIGSYS);
        });
        require(WIFSIGNALED(legacy) && WTERMSIG(legacy) == SIGSYS &&
                child_policy::classify_status(legacy) == Outcome::kRestricted, "legacy SIGSYS compatibility");
        int ignored = 0;
        require(!child_policy::wait_for_child(99999999, ignored) && errno == ECHILD,
                "wait failure must not use a zero-initialized clean status");
    }

    void test_setup_failures() {
        for (int denied_syscall : {SYS_rt_sigaction, SYS_rt_sigprocmask}) {
            const int status = run_child([=] {
                install_filter(denied_syscall, SECCOMP_RET_ERRNO | EPERM);
                if (!child_policy::prepare_sigsys_exit()) _exit(child_policy::kSetupUnavailableExitCode);
                _exit(90);
            });
            require(WIFEXITED(status) && WEXITSTATUS(status) == child_policy::kSetupUnavailableExitCode,
                    "handler installation or unblocking failure must prevent probing");
            require(child_policy::classify_status(status) == Outcome::kSetupUnavailable,
                    "setup failure is unavailable, never a completed probe");
        }
    }

    void test_ksu_denied_semantics() {
        for (int denied_syscall : {SYS_reboot, SYS_rt_sigaction, SYS_rt_sigprocmask}) {
            const int status = run_child([=] {
                // The outer child models an Android process with an inherited filter.
                const unsigned int action = denied_syscall == SYS_reboot
                                            ? SECCOMP_RET_TRAP : SECCOMP_RET_ERRNO | EPERM;
                install_filter(denied_syscall, action);
                const auto result = duckdetector::nativeroot::run_ksu_supercall_probe();
                require(result.checked_count == 1 && result.denied_count == 1,
                        "KSU restriction/setup failure must preserve denied evidence");
                require(result.hit_count == 0 && !result.flags.kernel_su && result.findings.empty(),
                        "KSU restriction must not fabricate a root hit");
            });
            require(child_policy::classify_status(status) == Outcome::kCompleted, "KSU blocked branch regression");
        }
        const int normal = run_child([] {
            // Never issue a privileged reboot in CI, even on an unexpectedly capable runner.
            install_filter(SYS_reboot, SECCOMP_RET_ERRNO | EPERM);
            const auto result = duckdetector::nativeroot::run_ksu_supercall_probe();
            require(result.checked_count == 1 && result.denied_count == 0 && result.hit_count == 0 &&
                    !result.flags.kernel_su, "preserve normal no-driver KSU branch");
        });
        require(child_policy::classify_status(normal) == Outcome::kCompleted, "KSU normal branch regression");
    }

    void test_large_pipe_payload_and_read_errors() {
        int fds[2];
        require(pipe(fds) == 0, "create large-payload test pipe");
        const std::string expected(1024 * 1024, 'x');  // Far larger than a Linux pipe buffer.
        const pid_t child = fork();
        require(child >= 0, "fork large-payload child");
        if (child == 0) {
            close(fds[0]);
            if (!child_policy::prepare_sigsys_exit()) _exit(child_policy::kSetupUnavailableExitCode);
            const bool written = child_policy::write_all(fds[1], expected.data(), expected.size());
            close(fds[1]);
            _exit(written ? 0 : child_policy::kPayloadFailedExitCode);
        }
        close(fds[1]);
        std::string payload;
        const bool read_ok = child_policy::read_to_end(fds[0], payload);
        close(fds[0]);
        int status = 0;
        require(child_policy::wait_for_child(child, status), "reap large-payload child after draining");
        require(read_ok && payload == expected && child_policy::classify_status(status) == Outcome::kCompleted,
                "large payload must drain completely without wait-before-read deadlock");
        require(!child_policy::read_to_end(-1, payload), "read error must fail rather than accept partial data");
        require(!child_policy::write_all(-1, expected.data(), expected.size()), "write error must fail");
        require(pipe(fds) == 0, "create incomplete-packet test pipe");
        require(child_policy::write_all(fds[1], "a", 1), "write incomplete packet");
        close(fds[1]);
        char packet[2];
        require(!child_policy::read_exact(fds[0], packet, sizeof(packet)), "partial packet EOF must fail");
        close(fds[0]);
    }

    volatile sig_atomic_t interrupted = 0;
    void interrupt_handler(int) {
        interrupted = 1;
    }

    void test_interrupted_read_and_wait() {
        struct sigaction action{};
        action.sa_handler = interrupt_handler;
        sigemptyset(&action.sa_mask);  // Intentionally omit SA_RESTART.
        struct sigaction original_action{};
        require(sigaction(SIGALRM, &action, &original_action) == 0, "install EINTR test handler");
        int fds[2];
        require(pipe(fds) == 0, "create EINTR test pipe");
        const pid_t child = fork();
        require(child >= 0, "fork EINTR test child");
        if (child == 0) {
            close(fds[0]);
            usleep(40000);
            const bool written = child_policy::write_all(fds[1], "a", 1);
            close(fds[1]);
            usleep(80000);
            _exit(written ? 0 : child_policy::kPayloadFailedExitCode);
        }
        close(fds[1]);
        struct itimerval timer{};
        timer.it_value.tv_usec = 1000;
        timer.it_interval.tv_usec = 1000;
        struct itimerval original_timer{};
        interrupted = 0;
        require(setitimer(ITIMER_REAL, &timer, &original_timer) == 0, "start recurring EINTR signals");
        char byte = 0;
        const bool read_ok = child_policy::read_exact(fds[0], &byte, 1);
        const bool read_interrupted = interrupted != 0;
        close(fds[0]);
        interrupted = 0;
        int status = 0;
        const bool waited = child_policy::wait_for_child(child, status);
        const bool wait_interrupted = interrupted != 0;
        require(setitimer(ITIMER_REAL, &original_timer, nullptr) == 0, "restore test timer");
        require(sigaction(SIGALRM, &original_action, nullptr) == 0, "restore EINTR handler");
        require(read_ok && byte == 'a' && read_interrupted, "retry read after EINTR");
        require(waited && wait_interrupted && child_policy::classify_status(status) == Outcome::kCompleted,
                "retry waitpid after EINTR and reap the actual child");
    }
}  // namespace

int main() {
    test_real_trap_preserves_parent();
    test_exit_classification();
    test_setup_failures();
    test_ksu_denied_semantics();
    test_large_pipe_payload_and_read_errors();
    test_interrupted_read_and_wait();
    std::puts("6 native seccomp regression groups passed");
    return EXIT_SUCCESS;
}
