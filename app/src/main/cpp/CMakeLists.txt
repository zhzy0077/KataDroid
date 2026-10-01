cmake_minimum_required(VERSION 3.22.1)
project(katadroid LANGUAGES CXX)

# Unmodified, pinned upstream rules/features/search. No desktop executable or
# alternate neural network implementation is compiled into the application.
set(KATAGO ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/katago/cpp)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(CORE_SOURCES
    core/global.cpp core/base64.cpp core/bsearch.cpp core/config_parser.cpp
    core/datetime.cpp core/fancymath.cpp core/fileutils.cpp core/hash.cpp
    core/logger.cpp core/makedir.cpp core/md5.cpp core/multithread.cpp
    core/parallel.cpp core/rand.cpp core/rand_helpers.cpp core/sha2.cpp
    core/test.cpp core/threadsafecounter.cpp core/threadsafequeue.cpp core/timer.cpp
    game/board.cpp game/rules.cpp game/boardhistory.cpp game/graphhash.cpp
    neuralnet/nninputs.cpp neuralnet/sgfmetadata.cpp neuralnet/modelversion.cpp
    neuralnet/nneval.cpp neuralnet/desc.cpp
    search/timecontrols.cpp search/searchparams.cpp search/mutexpool.cpp
    search/search.cpp search/searchnode.cpp search/searchresults.cpp
    search/searchhelpers.cpp search/searchexplorehelpers.cpp search/searchmirror.cpp
    search/searchmultithreadhelpers.cpp search/searchnnhelpers.cpp
    search/searchtimehelpers.cpp search/searchupdatehelpers.cpp
    search/distributiontable.cpp search/localpattern.cpp search/searchnodetable.cpp
    search/subtreevaluebiastable.cpp search/evalcache.cpp search/patternbonustable.cpp
    search/analysisdata.cpp search/reportedsearchvalues.cpp)
list(TRANSFORM CORE_SOURCES PREPEND "${KATAGO}/")
# Upstream SHA-2 expects BSD endian macros; bionic exposes them in endian.h.
set_source_files_properties(${KATAGO}/core/sha2.cpp ${KATAGO}/neuralnet/desc.cpp PROPERTIES COMPILE_OPTIONS "-include;endian.h")
add_library(katago_core STATIC ${CORE_SOURCES})
set_target_properties(katago_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(katago_core PUBLIC ${KATAGO} ${KATAGO}/external/filesystem-1.5.8/include)
target_compile_options(katago_core PRIVATE -O2 -ffunction-sections -fdata-sections)

add_library(katadroid SHARED katago_jni.cpp)
target_compile_options(katadroid PRIVATE -O2 -ffunction-sections -fdata-sections)
target_link_options(katadroid PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(katadroid PRIVATE katago_core z log)
