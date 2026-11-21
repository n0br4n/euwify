#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include "node.h"

#define LOG_TAG "NodeJS"

struct Redirect { int fd; int prio; };
static Redirect g_out, g_err;

static void *log_thread(void *arg) {
    Redirect *r = static_cast<Redirect *>(arg);
    char buf[2048];
    ssize_t n;
    while ((n = read(r->fd, buf, sizeof(buf) - 1)) > 0) {
        buf[n] = 0;
        __android_log_write(r->prio, LOG_TAG, buf);
    }
    return nullptr;
}

static void start_redirect() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    int p[2];
    pipe(p);
    dup2(p[1], STDOUT_FILENO);
    g_out = {p[0], ANDROID_LOG_INFO};
    pipe(p);
    dup2(p[1], STDERR_FILENO);
    g_err = {p[0], ANDROID_LOG_ERROR};
    pthread_t t1, t2;
    pthread_create(&t1, nullptr, log_thread, &g_out);
    pthread_detach(t1);
    pthread_create(&t2, nullptr, log_thread, &g_err);
    pthread_detach(t2);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_euwify_app_NodeRunner_startNodeWithArguments(
        JNIEnv *env, jclass, jobjectArray arguments) {
    start_redirect();

    jsize argc = env->GetArrayLength(arguments);
    int total = 0;
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        total += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }

    char *buffer = (char *) calloc(total, sizeof(char));
    char **argv = (char **) calloc(argc + 1, sizeof(char *));
    char *pos = buffer;
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        strcpy(pos, c);
        argv[i] = pos;
        pos += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }
    return (jint) node::Start((int) argc, argv);
}