cmake_minimum_required(VERSION 3.22.1)
project("eufyguard")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../libnode/bin/${ANDROID_ABI}/libnode.so)

add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../libnode/include/node)

target_link_libraries(${CMAKE_PROJECT_NAME} node android log)

target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")