// greenWave: traffic lights (along the route, or nearby in tracking mode) as coloured dots on the map.
// Uses the existing COLORED user-mark layer; nothing is persisted.
#include "app/organicmaps/sdk/Framework.hpp"

#include "map/user_mark.hpp"

#include "geometry/mercator.hpp"

extern "C"
{
// state per light: 0 = no data (grey), 1 = some data, still learning (amber),
// 2 = learned (green), 3 = live data (blue).
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetGreenWaveMarks(JNIEnv * env, jclass,
                                                                                    jdoubleArray latLons,
                                                                                    jintArray states)
{
  auto es = frm()->GetBookmarkManager().GetEditSession();
  es.ClearGroup(UserMark::Type::COLORED);
  es.SetIsVisible(UserMark::Type::COLORED, true);

  jsize const count = std::min(env->GetArrayLength(latLons) / 2, env->GetArrayLength(states));
  jdouble * coords = env->GetDoubleArrayElements(latLons, nullptr);
  jint * flags = env->GetIntArrayElements(states, nullptr);
  for (jsize i = 0; i < count; ++i)
  {
    auto * mark = es.CreateUserMark<ColoredMarkPoint>(mercator::FromLatLon(coords[2 * i], coords[2 * i + 1]));
    switch (flags[i])
    {
    case 1: mark->SetColor(dp::Color(224, 142, 11, 255)); break;
    case 2: mark->SetColor(dp::Color(46, 125, 50, 255)); break;
    case 3: mark->SetColor(dp::Color(21, 101, 192, 255)); break;
    default: mark->SetColor(dp::Color(117, 117, 117, 255)); break;
    }
    mark->SetRadius(7.0f);
  }
  env->ReleaseDoubleArrayElements(latLons, coords, JNI_ABORT);
  env->ReleaseIntArrayElements(states, flags, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeClearGreenWaveMarks(JNIEnv *, jclass)
{
  frm()->GetBookmarkManager().GetEditSession().ClearGroup(UserMark::Type::COLORED);
}
}  // extern "C"
