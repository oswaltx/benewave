// greenWave: route profile for bicycle routing (straight stretches, few traffic lights, little
// car traffic, or all of it), see libs/routing/bicycle_route_profile.hpp.
#include <jni.h>

#include "routing/bicycle_route_profile.hpp"

extern "C"
{
// |profile| is the BicycleRouteProfile value; unknown values fall back to Standard.
// Applies to every bicycle route built from now on.
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetGreenWaveRouteProfile(JNIEnv *, jclass,
                                                                                         jint profile)
{
  using routing::BicycleRouteProfile;
  bool const known = profile >= 0 && profile < static_cast<jint>(BicycleRouteProfile::Count);
  routing::SetBicycleRouteProfile(known ? static_cast<BicycleRouteProfile>(profile) : BicycleRouteProfile::Standard);
}
}  // extern "C"
