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
}  // extern "C"
