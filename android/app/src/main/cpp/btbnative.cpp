#include <stdint.h>
#include <jni.h>

static int rc = 0;

extern "C"
{

JNIEXPORT void JNICALL
Java_com_orthopteroid_btbchat_MainActivity_nativeTest(JNIEnv *env, jobject obj)
{
    rc = 0;
}

}