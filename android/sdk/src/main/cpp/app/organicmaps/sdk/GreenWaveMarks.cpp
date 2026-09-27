// greenWave: traffic lights (along the route, or nearby in tracking mode) on the map:
// the map style's traffic light icon on a disc coloured by what greenWave knows about it.
// Uses the COLORED user-mark layer; nothing is persisted.
//
// ColoredMarkPoint (a bare coloured disc) never showed up on Android, so the marks
// carry the same symbol as CoMaps' own route traffic lights (TrafficLightMark),
// which does render; the disc sits behind it.
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

dp::Color StateColor(int state)
{
  switch (state)
  {
  case 1: return dp::Color(224, 142, 11, 255);  // some data, still learning (amber)
  case 2: return dp::Color(46, 125, 50, 255);   // learned (green)
  case 3: return dp::Color(21, 101, 192, 255);  // live data (blue)
  default: return dp::Color(117, 117, 117, 255);  // no data (grey)
  }
}

class GreenWaveLightMark : public UserMark
{
public:
  explicit GreenWaveLightMark(m2::PointD const & ptOrg) : UserMark(ptOrg, UserMark::Type::COLORED) {}

  void SetState(int state)
  {
    SetDirty();
    m_state = state;
  }

  drape_ptr<SymbolNameZoomInfo> GetSymbolNames() const override
  {
    auto names = make_unique_dp<SymbolNameZoomInfo>();
    names->emplace(kMinZoom, "traffic_signals");
    names->emplace(kLargeIconZoom, "traffic_signals-l");
    return names;
  }

  drape_ptr<ColoredSymbolZoomInfo> GetColoredSymbols() const override
  {
    auto const vs = static_cast<float>(df::VisualParams::Instance().GetVisualScale());
    df::ColoredSymbolViewParams params;
    params.m_shape = df::ColoredSymbolViewParams::Shape::Circle;
    params.m_color = StateColor(m_state);
    params.m_outlineColor = dp::Color::White();
    params.m_outlineWidth = 1.5f * vs;
    params.m_radiusInPixels = 10.0f * vs;
    auto info = make_unique_dp<ColoredSymbolZoomInfo>();
    info->m_needOverlay = false;
    info->m_zoomInfo.emplace(kMinZoom, params);
    return info;
  }

  int GetMinZoom() const override { return kMinZoom; }
  dp::Anchor GetAnchor() const override { return dp::Center; }

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
    auto * mark = es.CreateUserMark<GreenWaveLightMark>(mercator::FromLatLon(coords[2 * i], coords[2 * i + 1]));
    mark->SetState(flags[i]);
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
