#define _DARWIN_C_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stop_requested = 0;

static int no_child(void) {
    dprintf(STDOUT_FILENO, "NO_CHILD\n");
    return 2;
}

static void request_stop(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}

static int wait_for_child(pid_t child, int *status, int options) {
    int result;
    do { result = waitpid(child, status, options); } while (result < 0 && errno == EINTR);
    return result;
}

static int stop_child(pid_t child) {
    int status = 0;
    if (kill(-child, SIGTERM) != 0 && errno != ESRCH) return 1;
    const struct timespec pause = {0, 100000000};
    for (int i = 0; i < 20; i++) {
        int result = wait_for_child(child, &status, WNOHANG);
        if (result == child) return 0;
        if (result < 0) return 1;
        nanosleep(&pause, NULL);
    }
    if (kill(-child, SIGKILL) != 0 && errno != ESRCH) return 1;
    return wait_for_child(child, &status, 0) == child ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc < 3) return no_child();
    int lock = open(argv[1], O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) != 0) return no_child();
    if (strcmp(argv[2], "--hold-lock") == 0) {
        if (dprintf(STDOUT_FILENO, "LOCKED\n") < 0) return 2;
        char byte;
        while (read(STDIN_FILENO, &byte, 1) > 0) { }
        return 0;
    }
    signal(SIGPIPE, SIG_IGN);
    struct sigaction handler = {0};
    handler.sa_handler = request_stop;
    sigemptyset(&handler.sa_mask);
    if (sigaction(SIGTERM, &handler, NULL) != 0 || sigaction(SIGINT, &handler, NULL) != 0) return no_child();
    pid_t child = fork();
    if (child < 0) return no_child();
    if (child == 0) {
        if (setpgid(0, 0) != 0) _exit(127);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        int nullfd = open("/dev/null", O_RDWR);
        if (nullfd < 0) _exit(127);
        if (dup2(nullfd, STDIN_FILENO) < 0 || dup2(nullfd, STDOUT_FILENO) < 0 || dup2(nullfd, STDERR_FILENO) < 0) _exit(127);
        if (nullfd > STDERR_FILENO) close(nullfd);
        execv(argv[2], &argv[2]);
        _exit(127);
    }
    if (setpgid(child, child) != 0 && errno != EACCES && errno != ESRCH) {
        kill(child, SIGKILL);
        wait_for_child(child, &(int){0}, 0);
        return no_child();
    }
    if (dprintf(STDOUT_FILENO, "READY %ld\n", (long)child) < 0) {
        stop_child(child);
        return 2;
    }
    for (;;) {
        int status = 0;
        int result = wait_for_child(child, &status, WNOHANG);
        if (result == child) return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
        if (result < 0) return 2;
        if (stop_requested) return stop_child(child);
        struct pollfd control = { .fd = STDIN_FILENO, .events = POLLIN };
        int ready = poll(&control, 1, 100);
        if (ready < 0 && errno != EINTR) return stop_child(child);
        if (ready > 0 && (control.revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL))) {
            char byte;
            if (read(STDIN_FILENO, &byte, 1) <= 0 || byte == 's') return stop_child(child);
        }
    }
}
