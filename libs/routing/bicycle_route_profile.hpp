#pragma once

#include "routing/road_penalty.hpp"

#include "routing_common/vehicle_model.hpp"

#include <cstdint>
#include <optional>
#include <string>

namespace routing
{
// greenWave: route profiles for bicycles, so that a fast rider can keep the pace even if the
// route gets a bit longer. A profile only reweights the route search (EdgeEstimator::Purpose::Weight);
// travel times (ETA) stay those of the standard bicycle model.
enum class BicycleRouteProfile : uint8_t
{
  Standard = 0,
  Straight,        // few turns, long straight stretches
  FewSignals,      // few traffic lights
  LessCarTraffic,  // keep away from main roads
  Smooth,          // all of the above, each a bit milder
  Count
};

struct BicycleRouteFactors
{
  // Multiplies the turn penalty. >= 1.
  double m_turn = 1.0;
  // Multiply the time penalty of traffic signals and level crossings, resp. stop/give way and
  // pedestrian crossings. >= 1.
  double m_controlledJunction = 1.0;
  double m_uncontrolledJunction = 1.0;
  // Multiply the weight speed on trunk/primary, secondary and tertiary roads (with links), a proxy
  // for car traffic. <= 1, so the A* heuristic stays admissible.
  double m_mainRoadSpeed = 1.0;
  double m_secondaryRoadSpeed = 1.0;
  double m_tertiaryRoadSpeed = 1.0;
};

BicycleRouteFactors const & GetBicycleRouteFactors(BicycleRouteProfile profile);

// The profile used by every bicycle route search from now on. Thread safe.
void SetBicycleRouteProfile(BicycleRouteProfile profile);
BicycleRouteProfile GetBicycleRouteProfile();

double GetRoadPenaltyFactor(BicycleRouteFactors const & factors, RoadPenalty::Type type);
double GetRoadSpeedFactor(BicycleRouteFactors const & factors, std::optional<HighwayType> type);

// Factor for a penalty in a bicycle route's travel time (ETA), independent of the profile.
// Traffic signals count 0 here: the app adds the expected wait per light itself, from learned
// programs where it has them (greenWave SignalWait). CoMaps only knows signals on car roads, so
// a fixed time here would miss those on cycle paths and could not be told apart from them.
double GetRoadPenaltyEtaFactor(RoadPenalty::Type type);

std::string DebugPrint(BicycleRouteProfile profile);
}  // namespace routing
