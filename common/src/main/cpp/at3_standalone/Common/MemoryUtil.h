#pragma once

#include <cstdlib>
#include <malloc.h>

inline void *AllocateAlignedMemory(size_t size, size_t alignment) {
    if (size == 0) return nullptr;
    size_t align = (alignment < sizeof(void *)) ? sizeof(void *) : alignment;
    void *ptr = nullptr;
    if (posix_memalign(&ptr, align, size) != 0) {
        return nullptr;
    }
    return ptr;
}

inline void FreeAlignedMemory(void *ptr) {
    if (ptr != nullptr) {
        free(ptr);
    }
}
