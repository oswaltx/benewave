// greenWave: traffic signal nodes from the downloaded maps, so the lights along a route
// don't depend on the public Overpass API (slow or down at times, and it would learn
// every route). Same feature type CoMaps uses for its own route traffic lights.
#include "app/organicmaps/sdk/Framework.hpp"

#include "indexer/classificator.hpp"
#include "indexer/data_source.hpp"
#include "indexer/feature.hpp"
#include "indexer/feature_algo.hpp"
#include "indexer/feature_data.hpp"
#include "indexer/scales.hpp"

#include "geometry/mercator.hpp"

#include <map>
#include <string>
#include <vector>

extern "C"
{
// All highway=traffic_signals nodes in the rectangle as [lat0, lon0, lat1, lon1, ...].
// Empty where no map is downloaded. Safe to call off the main thread.
JNIEXPORT jdoubleArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGreenWaveTrafficSignals(
    JNIEnv * env, jclass, jdouble minLat, jdouble minLon, jdouble maxLat, jdouble maxLon)
{
  static uint32_t const kType = classif().GetTypeByPath({"highway", "traffic_signals"});
  auto const minPt = mercator::FromLatLon(minLat, minLon);
  auto const maxPt = mercator::FromLatLon(maxLat, maxLon);
  m2::RectD const rect(minPt.x, minPt.y, maxPt.x, maxPt.y);

  std::vector<double> latLons;
  frm()->GetDataSource().ForEachInRect([&](FeatureType & ft)
  {
    if (ft.GetGeomType() != feature::GeomType::Point)
      return;
    if (!feature::TypesHolder(ft).Has(kType))
      return;
    auto const ll = mercator::ToLatLon(feature::GetCenter(ft));
    latLons.push_back(ll.m_lat);
    latLons.push_back(ll.m_lon);
  }, rect, scales::GetUpperScale());

  jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(latLons.size()));
  env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(latLons.size()), latLons.data());
  return result;
}

// Ways a bicycle can use in the rectangle, for map matching the rider's track:
// [count, then per way: kind, id, pointCount, lat0, lon0, lat1, lon1, ...].
// kind: 0 carriageway, 1 cycleway, 2 footway, 3 path, 4 other highway.
// id: unique within this result (mwm ordinal * 2^32 + feature index).
JNIEXPORT jdoubleArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGreenWaveWays(
    JNIEnv * env, jclass, jdouble minLat, jdouble minLon, jdouble maxLat, jdouble maxLon)
{
  static std::map<std::string, int> const kKinds = {
      {"motorway", -1}, {"motorway_link", -1}, {"trunk", 0}, {"trunk_link", 0},
      {"primary", 0}, {"primary_link", 0}, {"secondary", 0}, {"secondary_link", 0},
      {"tertiary", 0}, {"tertiary_link", 0}, {"unclassified", 0}, {"residential", 0},
      {"living_street", 0}, {"service", 0}, {"road", 0}, {"busway", 0},
      {"cycleway", 1}, {"footway", 2}, {"pedestrian", 2}, {"steps", 2},
      {"path", 3}, {"track", 3}, {"bridleway", 3}};
  auto const minPt = mercator::FromLatLon(minLat, minLon);
  auto const maxPt = mercator::FromLatLon(maxLat, maxLon);
  m2::RectD const rect(minPt.x, minPt.y, maxPt.x, maxPt.y);
  auto const & c = classif();

  std::vector<double> out{0.0};
  std::map<std::string, int> mwms;
  frm()->GetDataSource().ForEachInRect([&](FeatureType & ft)
  {
    if (ft.GetGeomType() != feature::GeomType::Line)
      return;
    int kind = -2;
    for (uint32_t const t : feature::TypesHolder(ft))
    {
      // "highway-primary", "highway-cycleway-bridge", ...
      std::string const name = c.GetReadableObjectName(t);
      if (name.rfind("highway-", 0) != 0)
        continue;
      auto const rest = name.substr(8);
      auto const it = kKinds.find(rest.substr(0, rest.find('-')));
      kind = it == kKinds.end() ? 4 : it->second;
      break;
    }
    if (kind < 0)
      return;  // not a highway, or not for bicycles
    ft.ParseGeometry(FeatureType::BEST_GEOMETRY);
    size_t const n = ft.GetPointsCount();
    if (n < 2)
      return;
    auto const & fid = ft.GetID();
    auto const mwm = mwms.emplace(fid.GetMwmName(), static_cast<int>(mwms.size())).first->second;
    out.push_back(kind);
    out.push_back(static_cast<double>(mwm) * 4294967296.0 + fid.m_index);
    out.push_back(static_cast<double>(n));
    for (size_t i = 0; i < n; ++i)
    {
      auto const ll = mercator::ToLatLon(ft.GetPoint(i));
      out.push_back(ll.m_lat);
      out.push_back(ll.m_lon);
    }
    out[0] += 1;
  }, rect, scales::GetUpperScale());

  jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(out.size()));
  env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
  return result;
}
}  // extern "C"
