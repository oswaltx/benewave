// greenWave: traffic lights (along the route, or nearby in tracking mode) on the map:
// the map style's traffic light icon with a dot coloured by what greenWave knows about it.
// Uses the COLORED user-mark layer; nothing is persisted.
//
// ColoredMarkPoint (a bare coloured disc) never showed up on Android, so the marks
// carry the same symbol as CoMaps' own route traffic lights (TrafficLightMark),
// which does render, plus a tinted dot for the state.
#include "app/organicmaps/sdk/Framework.hpp"

#include "map/user_mark.hpp"

#include "drape_frontend/visual_params.hpp"

#include "geometry/mercator.hpp"

#include "base/logging.hpp"

namespace
{
// From zoom 13 on, so the lights are visible in the route overview too.
int constexpr kMinZoom = 13;
int constexpr kLargeIconZoom = 17;

/// The map style's traffic light icon, like CoMaps' own route traffic lights.
class GreenWaveLightMark : public UserMark
{
public:
  explicit GreenWaveLightMark(m2::PointD const & ptOrg) : UserMark(ptOrg, UserMark::Type::COLORED) {}

  drape_ptr<SymbolNameZoomInfo> GetSymbolNames() const override
  {
    auto names = make_unique_dp<SymbolNameZoomInfo>();
    names->emplace(kMinZoom, "traffic_signals");
    names->emplace(kLargeIconZoom, "traffic_signals-l");
    return names;
  }

  int GetMinZoom() const override { return kMinZoom; }
};

/// Small dot at the icon's upper right in the colour of what greenWave knows about
/// the light. A tinted bookmark dot: bare coloured discs (ColoredMarkPoint) are not
/// drawn on Android, tinted symbols are.
class GreenWaveStateMark : public UserMark
{
public:
  explicit GreenWaveStateMark(m2::PointD const & ptOrg) : UserMark(ptOrg, UserMark::Type::COLORED) {}

  void SetState(int state)
  {
    SetDirty();
    m_state = state;
  }

  drape_ptr<SymbolNameZoomInfo> GetSymbolNames() const override
  {
    auto names = make_unique_dp<SymbolNameZoomInfo>();
    names->emplace(kMinZoom, "bookmark-default-xs");
    return names;
  }

  df::ColorConstant GetColorConstant() const override
  {
    switch (m_state)
    {
    case 1: return "BookmarkOrange";  // some data, still learning
    case 2: return "BookmarkGreen";   // learned
    case 3: return "BookmarkBlue";    // live data
    default: return "BookmarkGray";   // no data
    }
  }

  m2::PointD GetPixelOffset() const override
  {
    auto const vs = df::VisualParams::Instance().GetVisualScale();
    return {7.0 * vs, -7.0 * vs};
  }

  // Drawn over the traffic light icon instead of being cut off by it.
  bool GetDepthTestEnabled() const override { return false; }
  int GetMinZoom() const override { return kMinZoom; }

private:
  int m_state = 0;
};
}  // namespace

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
    auto const point = mercator::FromLatLon(coords[2 * i], coords[2 * i + 1]);
    es.CreateUserMark<GreenWaveLightMark>(point);
    es.CreateUserMark<GreenWaveStateMark>(point)->SetState(flags[i]);
  }
  LOG(LINFO, ("greenWave marks:", count));
  env->ReleaseDoubleArrayElements(latLons, coords, JNI_ABORT);
  env->ReleaseIntArrayElements(states, flags, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeClearGreenWaveMarks(JNIEnv *, jclass)
{
  frm()->GetBookmarkManager().GetEditSession().ClearGroup(UserMark::Type::COLORED);
}
}  // extern "C"
