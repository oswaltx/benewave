// greenWave: traffic lights along the route as coloured dots on the map.
// Uses the existing COLORED user-mark layer; nothing is persisted.
#include "app/organicmaps/sdk/Framework.hpp"

#include "map/user_mark.hpp"

#include "geometry/mercator.hpp"

extern "C"
{
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetGreenWaveMarks(JNIEnv * env, jclass,
                                                                                    jdoubleArray latLons,
                                                                                    jbooleanArray learned)
{
  auto es = frm()->GetBookmarkManager().GetEditSession();
  es.ClearGroup(UserMark::Type::COLORED);
  es.SetIsVisible(UserMark::Type::COLORED, true);

  jsize const count = std::min(env->GetArrayLength(latLons) / 2, env->GetArrayLength(learned));
  jdouble * coords = env->GetDoubleArrayElements(latLons, nullptr);
  jboolean * flags = env->GetBooleanArrayElements(learned, nullptr);
  for (jsize i = 0; i < count; ++i)
  {
    auto * mark = es.CreateUserMark<ColoredMarkPoint>(mercator::FromLatLon(coords[2 * i], coords[2 * i + 1]));
    mark->SetColor(flags[i] ? dp::Color(46, 125, 50, 255) : dp::Color(117, 117, 117, 255));
    mark->SetRadius(7.0f);
  }
  env->ReleaseDoubleArrayElements(latLons, coords, JNI_ABORT);
  env->ReleaseBooleanArrayElements(learned, flags, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeClearGreenWaveMarks(JNIEnv *, jclass)
{
  frm()->GetBookmarkManager().GetEditSession().ClearGroup(UserMark::Type::COLORED);
}
}  // extern "C"
