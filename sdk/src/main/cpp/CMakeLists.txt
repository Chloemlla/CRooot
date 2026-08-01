cmake_minimum_required(VERSION 4.1.2)
project("crooot" LANGUAGES C CXX ASM)

# Both detector engines are linked into one SDK library consumed through the public CRooot name.
set(CROOOT_DUCK_ROOT "${CMAKE_CURRENT_LIST_DIR}/duckdetector")
add_subdirectory("${CROOOT_DUCK_ROOT}" duckdetector-build)

target_sources(chloemlla-crooot PRIVATE
    native_root_checks.cpp
    native_checks.cpp
)
target_include_directories(chloemlla-crooot PRIVATE "${CROOOT_DUCK_ROOT}")
target_compile_options(chloemlla-crooot PRIVATE -std=c++17 -fvisibility=hidden -fstack-protector-all -O2)
target_link_options(chloemlla-crooot PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(chloemlla-crooot android EGL GLESv2 log)
if(CROOOT_DUCK_IPO_SUPPORTED)
    set_property(TARGET chloemlla-crooot PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
endif()
