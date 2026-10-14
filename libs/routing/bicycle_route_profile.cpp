#include "routing/bicycle_route_profile.hpp"

#include <array>
#include <atomic>

namespace routing
{
namespace
{
// Starting values, to be tuned with real rides. Standard is the unchanged CoMaps model
// (bicycle: 15 s per traffic signal, 10 s per uncontrolled junction).
std::array<BicycleRouteFactors, static_cast<size_t>(BicycleRouteProfile::Count)> const kFactors = {{
    // turn, controlled, uncontrolled, main, secondary, tertiary
    {1.0, 1.0, 1.0, 1.0, 1.0, 1.0},     // Standard
    {4.0, 1.0, 1.0, 1.0, 1.0, 1.0},     // Straight
    {1.0, 4.0, 1.5, 1.0, 1.0, 1.0},     // FewSignals: 60 s per signal
    {1.0, 1.0, 1.0, 0.5, 0.65, 0.8},    // LessCarTraffic
    {2.5, 3.0, 1.5, 0.75, 0.85, 0.9},   // Smooth
}};

std::atomic<uint8_t> g_profile{static_cast<uint8_t>(BicycleRouteProfile::Standard)};
}  // namespace

BicycleRouteFactors const & GetBicycleRouteFactors(BicycleRouteProfile profile)
{
  auto const i = static_cast<size_t>(profile);
  return kFactors[i < kFactors.size() ? i : 0];
}

void SetBicycleRouteProfile(BicycleRouteProfile profile)
{
  if (profile >= BicycleRouteProfile::Count)
    profile = BicycleRouteProfile::Standard;
  g_profile.store(static_cast<uint8_t>(profile), std::memory_order_relaxed);
}

BicycleRouteProfile GetBicycleRouteProfile()
{
  return static_cast<BicycleRouteProfile>(g_profile.load(std::memory_order_relaxed));
}

double GetRoadPenaltyFactor(BicycleRouteFactors const & factors, RoadPenalty::Type type)
{
  switch (type)
  {
  case RoadPenalty::Type::ControlledJunction: return factors.m_controlledJunction;
  case RoadPenalty::Type::UncontrolledJunction: return factors.m_uncontrolledJunction;
  default: return 1.0;
  }
}

double GetRoadSpeedFactor(BicycleRouteFactors const & factors, std::optional<HighwayType> type)
{
  if (!type)
    return 1.0;
  switch (*type)
  {
  case HighwayType::HighwayTrunk:
  case HighwayType::HighwayTrunkLink:
  case HighwayType::HighwayPrimary:
  case HighwayType::HighwayPrimaryLink: return factors.m_mainRoadSpeed;
  case HighwayType::HighwaySecondary:
  case HighwayType::HighwaySecondaryLink: return factors.m_secondaryRoadSpeed;
  case HighwayType::HighwayTertiary:
  case HighwayType::HighwayTertiaryLink: return factors.m_tertiaryRoadSpeed;
  default: return 1.0;
  }
}

std::string DebugPrint(BicycleRouteProfile profile)
{
  switch (profile)
  {
  case BicycleRouteProfile::Standard: return "Standard";
  case BicycleRouteProfile::Straight: return "Straight";
  case BicycleRouteProfile::FewSignals: return "FewSignals";
  case BicycleRouteProfile::LessCarTraffic: return "LessCarTraffic";
  case BicycleRouteProfile::Smooth: return "Smooth";
  case BicycleRouteProfile::Count: break;
  }
  return "Unknown";
}
}  // namespace routing
