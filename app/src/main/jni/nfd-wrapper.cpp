/* -*- Mode:C++; c-file-style:"gnu"; indent-tabs-mode:nil; -*- */
/**
 * Copyright (c) 2015-2016 Regents of the University of California
 *
 * This file is part of NFD (Named Data Networking Forwarding Daemon) Android.
 * See AUTHORS.md for complete list of NFD Android authors and contributors.
 *
 * NFD Android is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * NFD Android is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * NFD Android, e.g., in COPYING.md file.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "nfd-wrapper.hpp"
#include <string>
#include "daemon/nfd.hpp"
#include "rib/service.hpp"

#include "core/global-io.hpp"
#include "core/config-file.hpp"
#include "core/logger.hpp"
#include "core/privilege-helper.hpp"

#include <stdlib.h>
#include <boost/property_tree/info_parser.hpp>
#include <boost/thread.hpp>
#include <mutex>

// ------------- DTN
#include "NFD/daemon/face/dtn-transport.hpp"
#include "NFD/daemon/face/dtn-channel.hpp"

//#include <android/log.h>

//#define LOG_TAG2 "DEBFIN"
//#define LOGD2(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG2, __VA_ARGS__)
// ------------- DTN

NFD_LOG_INIT("NfdWrapper");

namespace nfd {


// A little bit of cheating to make sure NFD can be properly restarted

namespace scheduler {
// defined in scheduler.cpp
void
resetGlobalScheduler();
} // namespace scheduler

void
resetGlobalIoService();


class Runner
{
public:
  Runner()
    : m_io(nullptr)
  {
    std::string initialConfig =
      "general\n"
      "{\n"
      "}\n"
      "\n"
      "log\n"
      "{\n"
      "  default_level ALL\n"
      "  NameTree INFO\n"
      "  BestRouteStrategy2 INFO\n"
      "  InternalFace INFO\n"
      "  Forwarder INFO\n"
      "  ContentStore INFO\n"
      "  DeadNonceList INFO\n"
      "}\n"
      "tables\n"
      "{\n"
      "  cs_max_packets 100\n"
      "\n"
      "  strategy_choice\n"
      "  {\n"
      "    /               /localhost/nfd/strategy/best-route\n"
      "    /localhost      /localhost/nfd/strategy/multicast\n"
      "    /localhost/nfd  /localhost/nfd/strategy/best-route\n"
      "    /ndn/broadcast  /localhost/nfd/strategy/multicast\n"
      "    /ndn/multicast  /localhost/nfd/strategy/multicast\n"
      "  }\n"
      "}\n"
      "\n"
      "face_system\n"
      "{\n"
      "  tcp\n"
      "  {\n"
      "    listen yes\n"
      "    port 6363\n"
      "    enable_v4 yes\n"
      "    enable_v6 yes\n"
      "  }\n"
      "\n"
      "  udp\n"
      "  {\n"
      "    port 6363\n"
      "    enable_v4 yes\n"
      "    enable_v6 yes\n"
      "    idle_timeout 600\n"
      "    keep_alive_interval 25\n"
      "    mcast no\n"
      "  }\n"
      "  dtn\n"
      "  {\n"
      "    host localhost\n"
      "    port 4550\n"
      "    endpointPrefix dtn-node1\n"
      "    endpointAffix /nfd\n"
      "  }\n"
      "  websocket\n"
      "  {\n"
      "    listen yes\n"
      "    port 9696\n"
      "    enable_v4 yes\n"
      "    enable_v6 yes\n"
      "  }\n"
      "}\n"
      "\n"
      "authorizations\n"
      "{\n"
      "  authorize\n"
      "  {\n"
      "    certfile any\n"
      "    privileges\n"
      "    {\n"
      "      faces\n"
      "      fib\n"
      "      strategy-choice\n"
      "    }\n"
      "  }\n"
      "}\n"
      "\n"
      "rib\n"
      "{\n"
      "  localhost_security\n"
      "  {\n"
      "    trust-anchor\n"
      "    {\n"
      "      type any\n"
      "    }\n"
      "  }\n"
      "\n"
      "  auto_prefix_propagate\n"
      "  {\n"
      "    cost 15\n"
      "    timeout 10000\n"
      "    refresh_interval 300\n"
      "    base_retry_wait 50\n"
      "    max_retry_wait 3600\n"
      "  }\n"
      "}\n"
      "\n";

    std::istringstream input(initialConfig);
    boost::property_tree::read_info(input, m_config);

    std::unique_lock<std::mutex> lock(m_pointerMutex);
    m_nfd.reset(new Nfd(m_config, m_keyChain));
    m_nrd.reset(new rib::Service(m_config, m_keyChain));

    m_nfd->initialize();
    m_nrd->initialize();
  }

  ~Runner()
  {
    stop();
    m_io->reset();
  }

  void
  start()
  {
    {
      std::unique_lock<std::mutex> lock(m_pointerMutex);
      m_io = &getGlobalIoService();
    }

    m_io->run();
    m_io->reset();
  }

  void
  stop()
  {
    std::unique_lock<std::mutex> lock(m_pointerMutex);

    m_io->post([this] {
        m_io->stop();
        this->m_nrd.reset();
        this->m_nfd.reset();
      });
  }

private:
  std::mutex m_pointerMutex;
  boost::asio::io_service* m_io;
  ndn::KeyChain m_keyChain;
  unique_ptr<Nfd> m_nfd; // will use globalIoService
  unique_ptr<rib::Service> m_nrd; // will use globalIoService

  nfd::ConfigSection m_config;
};

static unique_ptr<Runner> g_runner;
static boost::thread g_thread;
static std::map<std::string, std::string> g_params;

} // namespace nfd




std::map<std::string, std::string>
getParams(JNIEnv* env, jobject jParams)
{
  std::map<std::string, std::string> params;

  jclass jcMap = env->GetObjectClass(jParams);
  jclass jcSet = env->FindClass("java/util/Set");
  jclass jcIterator = env->FindClass("java/util/Iterator");
  jclass jcMapEntry = env->FindClass("java/util/Map$Entry");

  jmethodID jcMapEntrySet      = env->GetMethodID(jcMap,      "entrySet", "()Ljava/util/Set;");
  jmethodID jcSetIterator      = env->GetMethodID(jcSet,      "iterator", "()Ljava/util/Iterator;");
  jmethodID jcIteratorHasNext  = env->GetMethodID(jcIterator, "hasNext",  "()Z");
  jmethodID jcIteratorNext     = env->GetMethodID(jcIterator, "next",     "()Ljava/lang/Object;");
  jmethodID jcMapEntryGetKey   = env->GetMethodID(jcMapEntry, "getKey",   "()Ljava/lang/Object;");
  jmethodID jcMapEntryGetValue = env->GetMethodID(jcMapEntry, "getValue", "()Ljava/lang/Object;");

  jobject jParamsEntrySet = env->CallObjectMethod(jParams, jcMapEntrySet);
  jobject jParamsIterator = env->CallObjectMethod(jParamsEntrySet, jcSetIterator);
  jboolean bHasNext = env->CallBooleanMethod(jParamsIterator, jcIteratorHasNext);
  while (bHasNext) {
    jobject entry = env->CallObjectMethod(jParamsIterator, jcIteratorNext);

    jstring jKey = (jstring)env->CallObjectMethod(entry, jcMapEntryGetKey);
    jstring jValue = (jstring)env->CallObjectMethod(entry, jcMapEntryGetValue);

    const char* cKey = env->GetStringUTFChars(jKey, nullptr);
    const char* cValue = env->GetStringUTFChars(jValue, nullptr);

    params.insert(std::make_pair(cKey, cValue));

    env->ReleaseStringUTFChars(jKey, cKey);
    env->ReleaseStringUTFChars(jValue, cValue);

    bHasNext = env->CallBooleanMethod(jParamsIterator, jcIteratorHasNext);
  }

  return params;
}

/// DTN ____________________________________________________________________________________________
static JavaVM *jvm;


jclass DtnServiceClass;
jobject DtnServiceObject;
nfd::DtnChannel *pDtnChannel;
jmethodID mid;




jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    jclass cls = env->FindClass("net/named_data/nfd/service/DtnService");
    DtnServiceClass = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);

    mid = env->GetMethodID(DtnServiceClass, "sendMessage", "(Ljava/lang/String;[B)V");

    int gotVM = env->GetJavaVM(&jvm);




    return JNI_VERSION_1_6;
}



////////////////////////////////////////////////////////////////////////////////////////////////////

// convert jstring to std::string

std::string jstring2string(JNIEnv *env, jstring jStr) {
    if (!jStr)
        return "";

    std::vector<char> charsCode;
    const jchar *chars = env->GetStringChars(jStr, NULL);
    jsize len = env->GetStringLength(jStr);
    jsize i;

    for( i=0;i<len; i++){
        int code = (int)chars[i];
        charsCode.push_back( code );
    }
    env->ReleaseStringChars(jStr, chars);

    return  std::string(charsCode.begin(), charsCode.end());
}



// Queue Received Bundle
JNIEXPORT void JNICALL
Java_net_named_1data_nfd_service_DtnService_queueBundleJNI(
        JNIEnv *env,
        jobject obj,
        jstring rE,
        jbyteArray pl){
        /*
        // DELAY
                struct timespec res;
                clock_gettime(CLOCK_REALTIME, &res);
                double now_ms =  1000.0 * res.tv_sec + (double) res.tv_nsec / 1e6;
                LOGD2(",RCVstart, , ,%f",now_ms);
                // DELAY*/
        int len = (int)(env->GetArrayLength(pl));

        jbyte* temp = env->GetByteArrayElements(pl,NULL);

        uint8_t* payload = (uint8_t*) temp;
        // TODO 3 this is terrible
        std::string remoteEndpoint = jstring2string(env,rE);

        pDtnChannel->queueBundle(remoteEndpoint, payload, len);

        env->ReleaseByteArrayElements(pl, temp, 0);

        /*// DELAY
                //struct timespec res;
                clock_gettime(CLOCK_REALTIME, &res);
                now_ms =  1000.0 * res.tv_sec + (double) res.tv_nsec / 1e6;
                LOGD2(",RCVend, , , ,%f",now_ms);
                // DELAY*/

}

////////////////////////////////////////////////////////////////////////////////////////////////////

void
registerChannelToWrapper(nfd::DtnChannel *pChannel);

////////////////////////////////////////////////////////////////////////////////////////////////////

// Send packet to IBRDTN.
void
sendToIBRDTN(std::string destination, std::string data){

/*// DELAY
        struct timespec res;
        clock_gettime(CLOCK_REALTIME, &res);
        double now_ms =  1000.0 * res.tv_sec + (double) res.tv_nsec / 1e6;
        LOGD2(",SNDstart,%f",now_ms);
        // DELAY*/
        JNIEnv *env;
                if (jvm != NULL){

                    int attachOK = jvm->AttachCurrentThread(&env, NULL);
                    // TODO 1 was stupid
                    int envStat = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);

                }






        const char *cDestination = destination.c_str();
        jstring jDestination = env->NewStringUTF(cDestination);
        const uint8_t* ui8Data = reinterpret_cast<const uint8_t*>(&data[0]);

        jbyte* tempjData = (jbyte*)ui8Data;


        jbyteArray jData = env->NewByteArray(data.length());

        env->SetByteArrayRegion(jData,0,data.length(),tempjData);
        // TODO add null exception HANDLING
        env->CallVoidMethod(DtnServiceObject,mid,jDestination,jData);
        /*// DELAY
                //struct timespec res;
                clock_gettime(CLOCK_REALTIME, &res);
                now_ms =  1000.0 * res.tv_sec + (double) res.tv_nsec / 1e6;
                LOGD2(",SNDend, ,%f",now_ms);
                // DELAY*/
        jvm->DetachCurrentThread();
}

////////////////////////////////////////////////////////////////////////////////////////////////////

// Get the DTN channel object so that it can be passed by queueBundle
void
registerChannelToWrapper(nfd::DtnChannel *pChannel){
    pDtnChannel = pChannel;
}

// Get the Java VM for later calls (sendToIBRDTN, startJavaClient (?))
JNIEXPORT void JNICALL
Java_net_named_1data_nfd_service_DtnService_initializeNativeInterface(
        JNIEnv *env,
        jobject obj){

    int gotVM = env->GetJavaVM(&jvm);

    DtnServiceObject = env->NewGlobalRef(obj);

}



/// DTN ____________________________________________________________________________________________

JNIEXPORT void JNICALL
Java_net_named_1data_nfd_service_NfdService_startNfd(JNIEnv* env, jclass, jobject jParams)
{


  if (nfd::g_runner.get() == nullptr) {
    nfd::g_params = getParams(env, jParams);

    // set/update HOME environment variable
    ::setenv("HOME", nfd::g_params["homePath"].c_str(), true);
    NFD_LOG_INFO("Use [" << nfd::g_params["homePath"] << "] as a security storage");

    nfd::g_thread = boost::thread([] {
        nfd::scheduler::resetGlobalScheduler();
        nfd::resetGlobalIoService();

        NFD_LOG_INFO("Starting NFD...");
        try {
          nfd::g_runner.reset(new nfd::Runner());
          nfd::g_runner->start();
        }
        catch (const std::exception& e) {
          NFD_LOG_FATAL(e.what());
        }
        catch (const nfd::PrivilegeHelper::Error& e) {
          NFD_LOG_FATAL("PrivilegeHelper: " << e.what());
        }
        catch (...) {
          NFD_LOG_FATAL("Unknown fatal error");
        }

        nfd::g_runner.reset();
        nfd::scheduler::resetGlobalScheduler();
        nfd::resetGlobalIoService();
        NFD_LOG_INFO("NFD stopped");
      });
  }
}

JNIEXPORT void JNICALL
Java_net_named_1data_nfd_service_NfdService_stopNfd(JNIEnv*, jclass)
{
  if (nfd::g_runner.get() != nullptr) {
    NFD_LOG_INFO("Stopping NFD...");
    nfd::g_runner->stop();
    // do not block anything
  }
}

JNIEXPORT jboolean JNICALL
Java_net_named_1data_nfd_service_NfdService_isNfdRunning(JNIEnv*, jclass)
{
    return nfd::g_runner.get() != nullptr;
}

JNIEXPORT jobject JNICALL
Java_net_named_1data_nfd_service_NfdService_getNfdLogModules(JNIEnv* env, jclass)
{
  jclass jcLinkedList = env->FindClass("java/util/LinkedList");
  jmethodID jcLinkedListConstructor = env->GetMethodID(jcLinkedList, "<init>", "()V");
  jmethodID jcLinkedListAdd = env->GetMethodID(jcLinkedList, "add", "(Ljava/lang/Object;)Z");

  jobject jModules = env->NewObject(jcLinkedList, jcLinkedListConstructor);

  for (const auto& module : nfd::LoggerFactory::getInstance().getModules()) {
    jstring jModule = env->NewStringUTF(module.c_str());
    env->CallBooleanMethod(jModules, jcLinkedListAdd, jModule);
  }

  return jModules;
}
