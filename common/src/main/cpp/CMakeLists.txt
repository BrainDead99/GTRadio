cmake_minimum_required(VERSION 3.16)
project(gtradio_at3)

set(AT3_DIR ${CMAKE_CURRENT_SOURCE_DIR}/at3_standalone)

add_library(gtradio_at3 SHARED
    at3_jni.cpp
    ${AT3_DIR}/atrac.cpp
    ${AT3_DIR}/atrac3.cpp
    ${AT3_DIR}/atrac3plus.cpp
    ${AT3_DIR}/atrac3plusdec.cpp
    ${AT3_DIR}/atrac3plusdsp.cpp
    ${AT3_DIR}/get_bits.cpp
    ${AT3_DIR}/compat.cpp
    ${AT3_DIR}/fft.cpp
    ${AT3_DIR}/mem.cpp
)

target_include_directories(gtradio_at3 PRIVATE ${AT3_DIR})
target_compile_options(gtradio_at3 PRIVATE -O2 -fPIC -Wno-deprecated-declarations -Wno-unused-parameter -Wno-unused-variable)

find_library(log-lib log)
target_link_libraries(gtradio_at3 ${log-lib})
