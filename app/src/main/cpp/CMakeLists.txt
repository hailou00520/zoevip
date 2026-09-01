cmake_minimum_required(VERSION 3.22.1)
project("zoevippatch")

add_library(zoevippatch SHARED zoevippatch.c)

find_library(log-lib log)

target_compile_options(zoevippatch PRIVATE -Wall -Wextra -O2 -fvisibility=hidden)
target_link_libraries(zoevippatch ${log-lib})
