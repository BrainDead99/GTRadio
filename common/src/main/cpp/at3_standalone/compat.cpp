#include <cstdarg>
#include <cstdio>
#include "compat.h"

void av_log(int level, const char *fmt, ...) {
    (void)level;
    (void)fmt;
}
