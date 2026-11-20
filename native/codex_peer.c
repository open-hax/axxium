#define _GNU_SOURCE
#define NAPI_VERSION 8
#include <node_api.h>

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#ifndef SO_PEERPIDFD
#error "The Codex peer guard requires Linux headers with SO_PEERPIDFD"
#endif

struct peer_guard {
    int path_fd;
    int socket_fd;
    int pid_fd;
    int executable_fd;
    bool socket_taken;
    char path[sizeof(((struct sockaddr_un *)0)->sun_path)];
    char executable_path[64];
    uintmax_t device;
    uintmax_t inode;
    struct ucred expected;
};

static const napi_type_tag peer_tag = {
    UINT64_C(0x146e1c87838b4536), UINT64_C(0xaaf0a3c371afd9a0)
};

static napi_value refuse(napi_env env, const char *code)
{
    napi_throw_error(env, code, "Codex Unix peer guard refused the operation");
    return NULL;
}

static void close_fd(int *fd)
{
    if (*fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

static void close_guard(struct peer_guard *guard)
{
    if (guard->socket_fd >= 0) {
        shutdown(guard->socket_fd, SHUT_RDWR);
    }
    close_fd(&guard->socket_fd);
    close_fd(&guard->pid_fd);
    close_fd(&guard->executable_fd);
    close_fd(&guard->path_fd);
}

static void finalize_guard(napi_env env, void *data, void *hint)
{
    (void)env;
    (void)hint;
    struct peer_guard *guard = data;
    close_guard(guard);
    free(guard);
}

static bool utf8_argument(napi_env env, napi_value value, char *out, size_t size)
{
    size_t length;
    return napi_get_value_string_utf8(env, value, NULL, 0, &length) == napi_ok
        && length > 0 && length < size
        && napi_get_value_string_utf8(env, value, out, size, &length) == napi_ok
        && memchr(out, '\0', length) == NULL;
}

static bool decimal_argument(napi_env env, napi_value value, uintmax_t *out)
{
    char text[3 * sizeof(uintmax_t) + 1];
    if (!utf8_argument(env, value, text, sizeof(text))
        || (text[0] == '0' && text[1] != '\0')) {
        return false;
    }
    uintmax_t number = 0;
    for (const char *p = text; *p != '\0'; ++p) {
        if (*p < '0' || *p > '9') {
            return false;
        }
        unsigned int digit = (unsigned int)(*p - '0');
        if (number > (UINTMAX_MAX - digit) / 10) {
            return false;
        }
        number = number * 10 + digit;
    }
    *out = number;
    return true;
}

static bool integer_argument(napi_env env, napi_value value, uint32_t min,
                             uint32_t max, uint32_t *out)
{
    double number;
    if (napi_get_value_double(env, value, &number) != napi_ok
        || !(number >= min && number <= max)
        || number != (double)(uint32_t)number) {
        return false;
    }
    *out = (uint32_t)number;
    return true;
}

static bool socket_inode_matches(const struct peer_guard *guard, const struct stat *info)
{
    return S_ISSOCK(info->st_mode)
        && (uintmax_t)info->st_dev == guard->device
        && (uintmax_t)info->st_ino == guard->inode;
}

static bool path_matches(const struct peer_guard *guard)
{
    struct stat current;
    struct stat pinned;
    return lstat(guard->path, &current) == 0 && socket_inode_matches(guard, &current)
        && fstat(guard->path_fd, &pinned) == 0 && socket_inode_matches(guard, &pinned);
}

static bool credentials_match(const struct peer_guard *guard)
{
    struct ucred actual;
    socklen_t length = sizeof(actual);
    return getsockopt(guard->socket_fd, SOL_SOCKET, SO_PEERCRED, &actual, &length) == 0
        && length == sizeof(actual)
        && actual.pid == guard->expected.pid
        && actual.uid == guard->expected.uid
        && actual.gid == guard->expected.gid;
}

static bool peer_alive(const struct peer_guard *guard)
{
    struct pollfd peer = { .fd = guard->pid_fd, .events = POLLIN };
    return guard->pid_fd >= 0 && poll(&peer, 1, 0) == 0;
}

static bool executable_matches(const struct peer_guard *guard)
{
    struct stat current;
    struct stat pinned;
    return stat(guard->executable_path, &current) == 0
        && fstat(guard->executable_fd, &pinned) == 0
        && S_ISREG(current.st_mode) && S_ISREG(pinned.st_mode)
        && current.st_dev == pinned.st_dev && current.st_ino == pinned.st_ino;
}

static const char *verify_guard(const struct peer_guard *guard)
{
    struct pollfd connection = { .fd = guard->socket_fd, .events = POLLRDHUP };
    if (guard->socket_fd < 0) {
        return "AXXIUM_PEER_CLOSED";
    }
    if (!path_matches(guard)) {
        return "AXXIUM_PEER_PATH";
    }
    if (!credentials_match(guard)) {
        return "AXXIUM_PEER_CREDENTIALS";
    }
    if (!peer_alive(guard)) {
        return "AXXIUM_PEER_PIDFD";
    }
    if (!executable_matches(guard)) {
        return "AXXIUM_PEER_EXECUTABLE";
    }
    if (!peer_alive(guard) || poll(&connection, 1, 0) != 0) {
        return "AXXIUM_PEER_CLOSED";
    }
    return NULL;
}

static int64_t monotonic_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return -1;
    }
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static const char *connect_pinned_socket(const struct peer_guard *guard, uint32_t timeout_ms)
{
    /* Resolve this pinned inode rather than re-resolving the configured path. */
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    int length = snprintf(address.sun_path, sizeof(address.sun_path),
                          "/proc/self/fd/%d", guard->path_fd);
    if (length < 0 || (size_t)length >= sizeof(address.sun_path)) {
        return "AXXIUM_PEER_SYSTEM";
    }
    int64_t start = monotonic_ms();
    if (start < 0) {
        return "AXXIUM_PEER_SYSTEM";
    }
    socklen_t address_len = offsetof(struct sockaddr_un, sun_path) + (size_t)length + 1;
    if (connect(guard->socket_fd, (const struct sockaddr *)&address, address_len) == 0) {
        return NULL;
    }
    if (errno != EINPROGRESS) {
        return "AXXIUM_PEER_CONNECT";
    }
    for (;;) {
        int64_t now = monotonic_ms();
        if (now < 0) {
            return "AXXIUM_PEER_SYSTEM";
        }
        int64_t remaining = start + timeout_ms - now;
        if (remaining <= 0) {
            return "AXXIUM_PEER_TIMEOUT";
        }
        struct pollfd pending = { .fd = guard->socket_fd, .events = POLLOUT };
        int ready = poll(&pending, 1, (int)remaining);
        if (ready < 0 && errno == EINTR) {
            continue;
        }
        if (ready == 0) {
            return "AXXIUM_PEER_TIMEOUT";
        }
        int socket_error;
        socklen_t size = sizeof(socket_error);
        if (ready < 0 || !(pending.revents & POLLOUT)
            || (pending.revents & (POLLERR | POLLHUP | POLLNVAL))
            || getsockopt(guard->socket_fd, SOL_SOCKET, SO_ERROR, &socket_error, &size) != 0
            || size != sizeof(socket_error) || socket_error != 0) {
            return "AXXIUM_PEER_CONNECT";
        }
        return NULL;
    }
}

static napi_value open_peer(napi_env env, napi_callback_info info)
{
    size_t argc = 7;
    napi_value args[7];
    struct peer_guard initial = { .path_fd = -1, .socket_fd = -1,
                                  .pid_fd = -1, .executable_fd = -1 };
    uint32_t uid, gid, pid, timeout;
    if (napi_get_cb_info(env, info, &argc, args, NULL, NULL) != napi_ok || argc != 7
        || !utf8_argument(env, args[0], initial.path, sizeof(initial.path))
        || initial.path[0] != '/'
        || !integer_argument(env, args[1], 0, UINT32_MAX - 1, &uid)
        || !integer_argument(env, args[2], 0, UINT32_MAX - 1, &gid)
        || !integer_argument(env, args[3], 1, INT_MAX, &pid)
        || !decimal_argument(env, args[4], &initial.device)
        || !decimal_argument(env, args[5], &initial.inode)
        || !integer_argument(env, args[6], 1, 30000, &timeout)) {
        return refuse(env, "AXXIUM_PEER_ARGUMENT");
    }
    initial.expected = (struct ucred){ .pid = (pid_t)pid, .uid = (uid_t)uid, .gid = (gid_t)gid };
    int length = snprintf(initial.executable_path, sizeof(initial.executable_path),
                          "/proc/%u/exe", pid);
    if (length < 0 || (size_t)length >= sizeof(initial.executable_path)) {
        return refuse(env, "AXXIUM_PEER_SYSTEM");
    }
    struct peer_guard *guard = malloc(sizeof(*guard));
    if (guard == NULL) {
        return refuse(env, "AXXIUM_PEER_SYSTEM");
    }
    *guard = initial;
    const char *failure = "AXXIUM_PEER_PATH";
    guard->path_fd = open(guard->path, O_PATH | O_NOFOLLOW | O_CLOEXEC);
    if (guard->path_fd < 0 || !path_matches(guard)) {
        goto fail;
    }
    failure = "AXXIUM_PEER_CONNECT";
    guard->socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (guard->socket_fd < 0) {
        goto fail;
    }
    failure = connect_pinned_socket(guard, timeout);
    if (failure != NULL) {
        goto fail;
    }
    failure = "AXXIUM_PEER_CREDENTIALS";
    if (!credentials_match(guard)) {
        goto fail;
    }
    failure = "AXXIUM_PEER_PIDFD";
    socklen_t pidfd_length = sizeof(guard->pid_fd);
    if (getsockopt(guard->socket_fd, SOL_SOCKET, SO_PEERPIDFD, &guard->pid_fd, &pidfd_length) != 0
        || pidfd_length != sizeof(guard->pid_fd) || !peer_alive(guard)) {
        goto fail;
    }
    int fd_flags = fcntl(guard->pid_fd, F_GETFD);
    if (fd_flags < 0 || !(fd_flags & FD_CLOEXEC)) {
        goto fail;
    }
    guard->executable_fd = open(guard->executable_path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    failure = verify_guard(guard);
    if (failure != NULL) {
        goto fail;
    }
    failure = "AXXIUM_PEER_SYSTEM";
    napi_value handle;
    if (napi_create_object(env, &handle) != napi_ok
        || napi_type_tag_object(env, handle, &peer_tag) != napi_ok
        || napi_wrap(env, handle, guard, finalize_guard, NULL, NULL) != napi_ok) {
        goto fail;
    }
    return handle;

fail:
    close_guard(guard);
    free(guard);
    return refuse(env, failure);
}

static struct peer_guard *get_guard(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value handle;
    napi_valuetype type;
    bool tagged = false;
    void *data = NULL;
    if (napi_get_cb_info(env, info, &argc, &handle, NULL, NULL) != napi_ok || argc != 1
        || napi_typeof(env, handle, &type) != napi_ok || type != napi_object
        || napi_check_object_type_tag(env, handle, &peer_tag, &tagged) != napi_ok || !tagged
        || napi_unwrap(env, handle, &data) != napi_ok || data == NULL) {
        refuse(env, "AXXIUM_PEER_ARGUMENT");
        return NULL;
    }
    return data;
}

static napi_value check_peer(napi_env env, napi_callback_info info)
{
    struct peer_guard *guard = get_guard(env, info);
    if (guard == NULL) {
        return NULL;
    }
    const char *failure = verify_guard(guard);
    if (failure != NULL) {
        return refuse(env, failure);
    }
    napi_value result;
    return napi_get_boolean(env, true, &result) == napi_ok
        ? result : refuse(env, "AXXIUM_PEER_SYSTEM");
}

static napi_value duplicate_guard_fd(napi_env env, napi_callback_info info, bool socket)
{
    struct peer_guard *guard = get_guard(env, info);
    if (guard == NULL) {
        return NULL;
    }
    const char *failure = verify_guard(guard);
    if (failure != NULL) {
        return refuse(env, failure);
    }
    if (socket && guard->socket_taken) {
        return refuse(env, "AXXIUM_PEER_HANDOFF");
    }
    int fd = fcntl(socket ? guard->socket_fd : guard->executable_fd, F_DUPFD_CLOEXEC, 3);
    napi_value result;
    if (fd < 0 || napi_create_int32(env, fd, &result) != napi_ok) {
        close_fd(&fd);
        return refuse(env, "AXXIUM_PEER_SYSTEM");
    }
    if (socket) {
        guard->socket_taken = true;
    }
    return result;
}

static napi_value take_socket_fd(napi_env env, napi_callback_info info)
{
    return duplicate_guard_fd(env, info, true);
}

static napi_value open_executable_fd(napi_env env, napi_callback_info info)
{
    return duplicate_guard_fd(env, info, false);
}

static napi_value close_peer(napi_env env, napi_callback_info info)
{
    struct peer_guard *guard = get_guard(env, info);
    if (guard == NULL) {
        return NULL;
    }
    close_guard(guard);
    napi_value result;
    return napi_get_undefined(env, &result) == napi_ok
        ? result : refuse(env, "AXXIUM_PEER_SYSTEM");
}

NAPI_MODULE_INIT()
{
    const napi_property_descriptor properties[] = {
        { .utf8name = "openPeer", .method = open_peer },
        { .utf8name = "checkPeer", .method = check_peer },
        { .utf8name = "takeSocketFd", .method = take_socket_fd },
        { .utf8name = "openExecutableFd", .method = open_executable_fd },
        { .utf8name = "closePeer", .method = close_peer },
    };
    return napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties)
        == napi_ok ? exports : refuse(env, "AXXIUM_PEER_SYSTEM");
}
