# Pixel One — motor de píxeis nativo
#
# C++17 estrito, otimização -O3 em Release (validada: a configuração de
# build do CMake corresponde ao buildType do AGP — Release usa a variante
# Release desta biblioteca; ver relatório D para a prova no build verbose).
# Nenhuma flag que comprometa a segurança de memória (sem -fno-stack-protector,
# sem UB-friendly pragmas); mantém-se stack protector e frame pointer.

cmake_minimum_required(VERSION 3.22.1)
project(pixelone_engine LANGUAGES CXX)

add_library(pixelone_engine SHARED pixelone_engine.cpp)

target_compile_features(pixelone_engine PRIVATE cxx_std_17)

# C++17 explícito também via flag (independente do padrão do compilador).
target_compile_options(pixelone_engine PRIVATE -std=c++17)

# Otimização por configuração:
#   Release        → -O3 (caminho de produção)
#   RelWithDebInfo → -O2 (perfil com símbolos)
#   Debug          → -O0 -g (depuração)
target_compile_options(pixelone_engine PRIVATE
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:RelWithDebInfo>:-O2>
    $<$<CONFIG:Debug>:-O0 -g>
)

# Proteções de memória mantidas SEMPRE (nunca desativadas para performance).
target_compile_options(pixelone_engine PRIVATE
    -fstack-protector-strong
    -fno-omit-frame-pointer
    -Wall
    -Wextra
)

# Nenhuma dependência fora do NDK (sem log nativo: a duração interna é
# devolvida no header e registada pelo Kotlin em PixelOnePerf/JNI).
