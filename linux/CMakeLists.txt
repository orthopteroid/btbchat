cmake_minimum_required(VERSION 3.15)
project(btbchat)

SET( CMAKE_VERBOSE_MAKEFILE ON )
set(CMAKE_CXX_STANDARD 17)
SET( CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/bin" )
IF( CMAKE_BUILD_TYPE STREQUAL "Debug" )
    SET( CMAKE_EXECUTABLE_SUFFIX_CXX "-debug" )
ENDIF()

SET( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti -fpermissive" )
SET( CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -DDEBUG -g" )
SET( CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -s" )

add_executable(btbchat main.cpp)
target_link_libraries(btbchat bluetooth)

#To grant network privs to target so it doesn't need to run as root, add post-build step:
#add_custom_command(
#    TARGET btbchat POST_BUILD
#    COMMAND /usr/bin/pkexec setcap -v CAP_NET_ADMIN+ep $<TARGET_FILE:btbchat>
#    COMMAND /usr/bin/pkexec setcap -v CAP_NET_SOCK+ep $<TARGET_FILE:btbchat>
#)
