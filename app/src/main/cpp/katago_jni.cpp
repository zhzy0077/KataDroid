#include <jni.h>
#include <array>
#include <atomic>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <string>
#include "neuralnet/nninterface.h"
#include "neuralnet/nneval.h"
#include "search/search.h"

using nlohmann::json;
namespace {
constexpr int AREA = 361;
constexpr int SPATIAL = AREA * 22;
constexpr int GLOBAL = 19;
constexpr int RAW_OUTPUTS = 730;
JavaVM* vm = nullptr;
std::once_flag initialized;

void initialize() {
  std::call_once(initialized, [] { Board::initHash(); ScoreValue::initTables(); });
}

std::string javaString(JNIEnv* env, jstring value) {
  if (!value) throw std::runtime_error("Missing string argument");
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) throw std::runtime_error("Cannot read string argument");
  std::string text(chars);
  env->ReleaseStringUTFChars(value, chars);
  return text;
}

void throwJava(JNIEnv* env, const std::exception& error) {
  if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

std::string takeJavaError(JNIEnv* env) {
  jthrowable error = env->ExceptionOccurred();
  env->ExceptionClear();
  jclass type = env->GetObjectClass(error);
  jmethodID toString = env->GetMethodID(type, "toString", "()Ljava/lang/String;");
  auto text = static_cast<jstring>(env->CallObjectMethod(error, toString));
  std::string message = "LiteRT inference failed";
  if (!env->ExceptionCheck() && text) message = javaString(env, text);
  env->ExceptionClear();
  if (text) env->DeleteLocalRef(text);
  env->DeleteLocalRef(type);
  env->DeleteLocalRef(error);
  return message;
}

// Each engine owns its callback. There is no process-wide current model, so a
// cancelled/recreated screen cannot accidentally evaluate through another one.
struct Bridge {
  jobject runtime;
  const std::string modelName;
  jmethodID evaluate;
  std::atomic<bool> failed{false};
  std::mutex errorMutex;
  std::string error;

  Bridge(JNIEnv* env, jobject obj, const std::string& name) : runtime(env->NewGlobalRef(obj)), modelName(name) {
    jclass type = env->GetObjectClass(obj);
    evaluate = env->GetMethodID(type, "evaluate", "([F[F)[F");
    env->DeleteLocalRef(type);
    if (!evaluate) throw std::runtime_error("Missing LiteRT callback");
  }
  ~Bridge() {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) env->DeleteGlobalRef(runtime);
  }
  void fail(const std::string& message) {
    std::lock_guard<std::mutex> lock(errorMutex);
    error = message;
    failed.store(true, std::memory_order_release);
  }
  void check() {
    if (failed.load(std::memory_order_acquire)) {
      std::lock_guard<std::mutex> lock(errorMutex);
      throw std::runtime_error(error);
    }
  }
};
thread_local Bridge* constructingBridge = nullptr;

struct Position {
  Board board{19, 19};
  BoardHistory history;
  Player next = P_BLACK;
  Position(JNIEnv* env, jstring input) {
    auto data = json::parse(javaString(env, input));
    float komi = data.at("komi").get<float>();
    if (!std::isfinite(komi) || komi < Rules::MIN_USER_KOMI || komi > Rules::MAX_USER_KOMI)
      throw std::runtime_error("Invalid komi");
    std::string ruleName = data.at("rules").get<std::string>();
    if (ruleName != "chinese" && ruleName != "korean" && ruleName != "japanese")
      throw std::runtime_error("Unsupported rules");
    std::vector<Move> placements;
    std::array<bool, AREA> occupied{};
    for (auto entry : {std::pair<const char*, Player>{"initialBlack", P_BLACK}, {"initialWhite", P_WHITE}}) {
      for (int point : data.at(entry.first).get<std::vector<int>>()) {
        if (point < 0 || point >= AREA || occupied[point]) throw std::runtime_error("Invalid setup stones");
        occupied[point] = true;
        placements.emplace_back(NNPos::posToLoc(point, 19, 19, 19, 19), entry.second);
      }
    }
    if (!board.setStonesFailIfNoLibs(placements)) throw std::runtime_error("Setup contains stones without liberties");
    next = data.at("initialPlayer").get<int>();
    if (next != P_BLACK && next != P_WHITE) throw std::runtime_error("Invalid initial player");
    history.clear(board, next, Rules::parseRulesWithoutKomi(ruleName, komi), 0);
    auto moves = data.at("moves").get<std::vector<int>>();
    auto colors = data.at("moveColors").get<std::vector<int>>();
    if (moves.size() > 2000 || moves.size() != colors.size()) throw std::runtime_error("Invalid record length");
    for (size_t i = 0; i < moves.size(); i++) {
      int point = moves[i];
      if (point < 0 || point > AREA) throw std::runtime_error("Invalid board coordinate");
      next = colors[i];
      if (next != P_BLACK && next != P_WHITE) throw std::runtime_error("Invalid move color");
      Loc loc = NNPos::posToLoc(point, 19, 19, 19, 19);
      // SGF records the color explicitly, including white-first problems and
      // repeated same-color moves. Keep normal turns fully strict; for an
      // out-of-turn color still reject occupancy/suicide using official Board.
      // makeBoardMoveAssumeLegal itself tracks the broken alternation in NN
      // history, as it does for upstream tolerant SGF playback.
      bool legal = next == history.presumedNextMovePla
          ? history.isLegal(board, loc, next)
          : board.isLegalIgnoringKo(loc, next, history.rules.multiStoneSuicideLegal);
      if (!legal)
        throw std::runtime_error("Illegal move " + std::to_string(i + 1) + ": " + Location::toString(loc, board));
      // A played record ends on two passes; internal search still uses KataGo's
      // normal territory-scoring encore to evaluate unsettled positions.
      history.makeBoardMoveAssumeLegal(board, loc, next, nullptr, true);
      if (i > 0 && point == AREA && moves[i-1] == AREA && colors[i-1] != next && !history.isNoResult)
        history.endAndScoreGameNow(board);
      next = getOpp(next);
    }
    next = data.at("nextPlayer").get<int>();
    if (next != P_BLACK && next != P_WHITE) throw std::runtime_error("Invalid next player");
    if (next != history.presumedNextMovePla) {
      // A PL edit switches the side without a played move. Use the same fresh
      // history semantics as upstream Search::setPlayerAndClearHistory, keeping
      // board captures and the original handicap compensation intact.
      const Rules rules = history.rules;
      const int handicap = history.computeNumHandicapStones();
      board.clearSimpleKoLoc();
      history.clear(board, next, rules, 0);
      history.setOverrideNumHandicapStones(handicap);
      history.setInitialTurnNumber(moves.size());
    }
  }
};
}  // namespace

// LiteRT backend for KataGo's unmodified NNEvaluator. Inputs and raw outputs use
// exactly the official ONNX export's conventions. Official code subsequently
// masks illegal policy moves and applies all value/score/ownership transforms.
struct LoadedModel { ModelDesc desc; Bridge* bridge; };
struct ComputeContext { Bridge* bridge; };
struct InputBuffers {};
struct ComputeHandle {
  Bridge* bridge;
  JNIEnv* env = nullptr;
  bool attached = false;
  jfloatArray spatial = nullptr;
  jfloatArray global = nullptr;
  std::array<float, SPATIAL> transformed{};
  std::array<float, RAW_OUTPUTS> raw{};
};

void NeuralNet::globalInitialize() { initialize(); }
void NeuralNet::globalCleanup() {}
void NeuralNet::printDevices() {}
std::string NeuralNet::getRuntimeBackendDetail(ConfigParser&) { return "litert"; }
NeuralNet::BatchPolicy NeuralNet::getBatchPolicy(ConfigParser&) { return BatchPolicy::FixedShape; }
int NeuralNet::getNumEffectiveDevices(ConfigParser&, const std::vector<int>&) { return 1; }
LoadedModel* NeuralNet::loadModelFile(const std::string& path, const std::string&) {
  if (!constructingBridge) throw std::runtime_error("No LiteRT runtime bound to model");
  auto model = std::make_unique<LoadedModel>();
  ModelDesc::loadFromFileMaybeGZipped(path, model->desc, "");
  if (model->desc.name != constructingBridge->modelName || model->desc.modelVersion != 8 ||
      model->desc.numInputChannels != 22 || model->desc.numInputGlobalChannels != 19)
    throw std::runtime_error("LiteRT export and version-8 model descriptor do not match");
  model->bridge = constructingBridge;
  return model.release();
}
void NeuralNet::freeLoadedModel(LoadedModel* model) { delete model; }
const ModelDesc& NeuralNet::getModelDesc(const LoadedModel* model) { return model->desc; }
ComputeContext* NeuralNet::createComputeContext(const std::vector<int>&, Logger*, int x, int y,
    const std::string&, enabled_t, const LoadedModel* model, ConfigParser&) {
  if (x != 19 || y != 19) throw std::runtime_error("LiteRT model requires 19x19");
  return new ComputeContext{model->bridge};
}
void NeuralNet::freeComputeContext(ComputeContext* context) { delete context; }
ComputeHandle* NeuralNet::createComputeHandle(ComputeContext* context, const LoadedModel*, Logger*,
    int batch, bool exact, bool nhwc, int, int) {
  if (batch != 1 || !exact || !nhwc) throw std::runtime_error("LiteRT requires exact 19x19 NHWC batch 1");
  auto handle = std::make_unique<ComputeHandle>();
  handle->bridge = context->bridge;
  if (vm->GetEnv(reinterpret_cast<void**>(&handle->env), JNI_VERSION_1_6) == JNI_EDETACHED) {
    if (vm->AttachCurrentThread(&handle->env, nullptr) != JNI_OK) throw std::runtime_error("Cannot attach NN thread");
    handle->attached = true;
  }
  handle->spatial = handle->env->NewFloatArray(SPATIAL);
  handle->global = handle->env->NewFloatArray(GLOBAL);
  return handle.release();
}
void NeuralNet::freeComputeHandle(ComputeHandle* handle) {
  if (!handle) return;
  handle->env->DeleteLocalRef(handle->spatial);
  handle->env->DeleteLocalRef(handle->global);
  if (handle->attached) vm->DetachCurrentThread();
  delete handle;
}
bool NeuralNet::isUsingFP16(const ComputeHandle*) { return false; }
bool NeuralNet::setIsWarmup(const ComputeHandle*, bool) { return false; }
InputBuffers* NeuralNet::createInputBuffers(const LoadedModel*, int, int, int) { return new InputBuffers; }
void NeuralNet::freeInputBuffers(InputBuffers* buffers) { delete buffers; }
void NeuralNet::getOutput(ComputeHandle* handle, InputBuffers*, int count, NNResultBuf** inputs,
    std::vector<NNOutput*>& outputs) {
  for (int i = 0; i < count; i++) {
    auto* input = inputs[i];
    auto* output = outputs[i];
    auto* env = handle->env;
    handle->raw.fill(0);
    if (!handle->bridge->failed.load(std::memory_order_acquire)) {
      SymmetryHelpers::copyInputsWithSymmetry(input->rowSpatialBuf.data(), handle->transformed.data(),
          1, 19, 19, 22, true, input->symmetry);
      env->SetFloatArrayRegion(handle->spatial, 0, SPATIAL, handle->transformed.data());
      env->SetFloatArrayRegion(handle->global, 0, GLOBAL, input->rowGlobalBuf.data());
      auto result = static_cast<jfloatArray>(env->CallObjectMethod(handle->bridge->runtime,
          handle->bridge->evaluate, handle->spatial, handle->global));
      if (env->ExceptionCheck()) handle->bridge->fail(takeJavaError(env));
      else if (!result || env->GetArrayLength(result) != RAW_OUTPUTS) handle->bridge->fail("Invalid LiteRT output shape");
      else env->GetFloatArrayRegion(result, 0, RAW_OUTPUTS, handle->raw.data());
      if (result) env->DeleteLocalRef(result);
    }
    // On an inference error, unblock the NNEvaluator client with finite values;
    // the search stop predicate observes failed and the JNI call throws. These
    // placeholders can never be returned to the UI as a successful analysis.
    const float* raw = handle->raw.data();
    SymmetryHelpers::copyOutputsWithSymmetry(raw, output->policyProbs, 1, 19, 19, input->symmetry);
    output->policyProbs[AREA] = raw[361];
    output->whiteWinProb = raw[362];
    output->whiteLossProb = raw[363];
    output->whiteNoResultProb = raw[364];
    output->whiteScoreMean = raw[365];
    output->whiteScoreMeanSq = raw[366];
    output->whiteLead = raw[367];
    output->varTimeLeft = raw[368];
    output->shorttermWinlossError = 0;
    output->shorttermScoreError = 0;
    if (output->whiteOwnerMap)
      SymmetryHelpers::copyOutputsWithSymmetry(raw + 369, output->whiteOwnerMap, 1, 19, 19, input->symmetry);
  }
}

namespace {
struct Engine {
  Bridge bridge;
  Logger logger;
  std::unique_ptr<NNEvaluator> evaluator;
  std::unique_ptr<Search> search;
  Engine(JNIEnv* env, jobject runtime, const std::string& path, const std::string& name) : bridge(env, runtime, name) {
    ConfigParser config;
    constructingBridge = &bridge;
    try {
      evaluator = std::make_unique<NNEvaluator>(name, path, "", &logger,
          1, 19, 19, true, true, 12, 8, false, "", enabled_t::False,
          1, std::vector<int>{-1}, "katadroid-nn", false, 0, config);
      constructingBridge = nullptr;
      evaluator->spawnServerThreads();
      SearchParams params;
      params.numThreads = 1;
      params.nodeTableShardsPowerOfTwo = 10;
      params.subtreeValueBiasTableNumShards = 1024;
      search = std::make_unique<Search>(params, evaluator.get(), &logger, "katadroid-search");
    } catch (...) { constructingBridge = nullptr; throw; }
  }
};
Engine& engine(jlong handle) {
  if (!handle) throw std::runtime_error("Engine is closed");
  return *reinterpret_cast<Engine*>(handle);
}
int point(Loc loc) { return NNPos::locToPos(loc, 19, 19, 19); }
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* javaVm, void*) { vm = javaVm; return JNI_VERSION_1_6; }

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_boardPosition(JNIEnv* env, jobject, jstring input) {
  try {
    initialize();
    Position p(env, input);
    std::array<jint, 728> data{};
    for (int i = 0; i < AREA; i++) data[i] = p.board.colors[NNPos::posToLoc(i, 19, 19, 19, 19)];
    data[361] = p.next;
    data[362] = p.board.numWhiteCaptures;
    data[363] = p.board.numBlackCaptures;
    data[364] = p.history.isGameFinished;
    data[365] = p.history.winner;
    for (int i = 0; i <= AREA; i++) data[366 + i] = p.history.isLegal(p.board, NNPos::posToLoc(i, 19, 19, 19, 19), p.next);
    jintArray result = env->NewIntArray(data.size());
    env->SetIntArrayRegion(result, 0, data.size(), data.data());
    return result;
  } catch (const std::exception& error) { throwJava(env, error); return nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_featuresPosition(JNIEnv* env, jobject, jstring input) {
  try {
    initialize();
    Position p(env, input);
    std::array<float, SPATIAL + GLOBAL> data{};
    NNInputs::fillRowV7(p.board, p.history, p.next, MiscNNInputParams(), 19, 19, true, data.data(), data.data() + SPATIAL);
    jfloatArray result = env->NewFloatArray(data.size());
    env->SetFloatArrayRegion(result, 0, data.size(), data.data());
    return result;
  } catch (const std::exception& error) { throwJava(env, error); return nullptr; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_create(JNIEnv* env, jobject, jobject runtime, jstring path, jstring name) {
  try { initialize(); return reinterpret_cast<jlong>(new Engine(env, runtime, javaString(env, path), javaString(env, name))); }
  catch (const std::exception& error) { throwJava(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_destroy(JNIEnv*, jobject, jlong handle) { delete reinterpret_cast<Engine*>(handle); }

extern "C" JNIEXPORT void JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_setPositionJson(JNIEnv* env, jobject, jlong handle, jstring input) {
  try { Position p(env, input); engine(handle).search->setPosition(p.next, p.board, p.history); }
  catch (const std::exception& error) { throwJava(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_clearSearchAndCache(JNIEnv* env, jobject, jlong handle) {
  try {
    auto& e = engine(handle);
    e.search->clearSearch();
    e.evaluator->clearCache();
  } catch (const std::exception& error) { throwJava(env, error); }
}

static jstring analyzeSearch(JNIEnv* env, jlong handle, jint visits, jint milliseconds, jobject cancelled) {
  try {
    auto& e = engine(handle);
    e.bridge.check();
    // Settings and automatic play bound each budget; interactive analysis can
    // keep increasing its cumulative visit target beyond that initial budget.
    if (visits < 1) throw std::runtime_error("Invalid search limit");
    jclass type = env->GetObjectClass(cancelled);
    jmethodID get = env->GetMethodID(type, "get", "()Z");
    env->DeleteLocalRef(type);
    std::function<bool()> stop = [&] {
      return e.bridge.failed.load(std::memory_order_acquire) || env->CallBooleanMethod(cancelled, get) == JNI_TRUE;
    };
    if (stop()) return nullptr;
    const auto& history = e.search->getRootHist();
    if (history.isGameFinished) {
      // Upstream search deliberately permits continued analysis after two
      // passes. The manual game is over here: report its official board score
      // without inventing visits or recommending moves beyond the end.
      const double blackWin = history.winner == P_BLACK ? 100.0 : history.winner == P_WHITE ? 0.0 : 50.0;
      json result{{"visits", 0}, {"gameFinished", true}, {"blackWinRate", blackWin},
          {"blackLead", -history.finalWhiteMinusBlackScore},
          {"blackScoreMean", -history.finalWhiteMinusBlackScore}, {"candidates", json::array()}};
      return env->NewStringUTF(result.dump().c_str());
    }
    auto params = e.search->searchParams;
    params.maxVisits = visits;
    params.maxTime = milliseconds > 0 ? milliseconds / 1000.0 : 1.0e20;
    e.search->setParamsNoClearing(params);
    e.search->runWholeSearch(e.search->getRootPla(), &stop);
    e.bridge.check();
    if (stop()) return nullptr;
    auto values = e.search->getRootValuesRequireSuccess();
    json result{{"visits", values.visits}, {"blackWinRate", 50.0 * (1.0 - values.winLossValue)},
                {"blackLead", -values.lead}, {"blackScoreMean", -values.expectedScore}, {"candidates", json::array()}};
    Loc chosen = e.search->getChosenMoveLoc();
    if (e.search->isLegalStrict(chosen, e.search->getRootPla())) result["bestMove"] = point(chosen);
    std::vector<AnalysisData> choices;
    // Upstream's depth excludes the first candidate move already in the PV.
    e.search->getAnalysisData(choices, 3, false, 7, false);
    for (const auto& choice : choices) {
      if (result["candidates"].size() == 3) break;
      if (choice.numVisits <= 0 || !e.search->isLegalStrict(choice.move, e.search->getRootPla())) continue;
      std::vector<int> pv;
      Board pvBoard = e.search->getRootBoard();
      BoardHistory pvHistory = history;
      Player pvPlayer = e.search->getRootPla();
      bool previousPass = !history.moveHistory.empty() && history.moveHistory.back().loc == Board::PASS_LOC;
      for (Loc move : choice.pv) {
        if (pvHistory.isGameFinished || !pvHistory.isLegal(pvBoard, move, pvPlayer)) break;
        pv.push_back(point(move));
        pvHistory.makeBoardMoveAssumeLegal(pvBoard, move, pvPlayer, nullptr, true);
        // Don't expose internal territory-scoring encore moves as playable PV.
        if (previousPass && move == Board::PASS_LOC) break;
        previousPass = move == Board::PASS_LOC;
        pvPlayer = getOpp(pvPlayer);
      }
      if (pv.empty()) pv.push_back(point(choice.move));
      result["candidates"].push_back({{"move", point(choice.move)}, {"visits", choice.numVisits},
          {"blackWinRate", 50.0 * (1.0 - choice.winLossValue)}, {"blackLead", -choice.lead}, {"pv", pv}});
    }
    return env->NewStringUTF(result.dump().c_str());
  } catch (const std::exception& error) { throwJava(env, error); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_analyze(JNIEnv* env, jobject, jlong handle, jint visits, jobject cancelled) {
  return analyzeSearch(env, handle, visits, 0, cancelled);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_analyzeForTime(JNIEnv* env, jobject, jlong handle, jint milliseconds, jint visits, jobject cancelled) {
  if (milliseconds < 1 || milliseconds > 5000) {
    throwJava(env, std::runtime_error("Invalid analysis interval"));
    return nullptr;
  }
  return analyzeSearch(env, handle, visits, milliseconds, cancelled);
}

// Also exposes the official postprocessed network for numerical integration
// tests; this follows the very same backend and legality path used by search.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_zhzy0077_katadroid_engine_NativeKataGo_evaluatePosition(JNIEnv* env, jobject, jlong handle, jstring input, jint symmetry) {
  try {
    auto& e = engine(handle);
    Position p(env, input);
    NNResultBuf buffer;
    MiscNNInputParams params;
    if (symmetry < 0 || symmetry > 7) throw std::runtime_error("Invalid symmetry");
    params.symmetry = symmetry;
    e.evaluator->evaluate(p.board, p.history, p.next, params, buffer, true, true);
    e.bridge.check();
    const auto& out = *buffer.result;
    json result{{"blackWinRate", 100.0 * (out.whiteLossProb + 0.5 * out.whiteNoResultProb)},
        {"blackLead", -out.whiteLead}, {"blackScoreMean", -out.whiteScoreMean},
        {"policy", std::vector<float>(out.policyProbs, out.policyProbs + AREA + 1)},
        {"whiteOwnership", std::vector<float>(out.whiteOwnerMap, out.whiteOwnerMap + AREA)}};
    return env->NewStringUTF(result.dump().c_str());
  } catch (const std::exception& error) { throwJava(env, error); return nullptr; }
}
