#include "testing/testing.hpp"

#include "routing/bicycle_route_profile.hpp"
#include "routing/edge_estimator.hpp"
#include "routing/geometry.hpp"
#include "routing/segment.hpp"

#include "routing_common/vehicle_model.hpp"

#include <memory>

namespace bicycle_route_profile_test
{
using namespace routing;
using Purpose = EdgeEstimator::Purpose;

auto constexpr kProfiles = {BicycleRouteProfile::Standard, BicycleRouteProfile::Straight,
                            BicycleRouteProfile::FewSignals, BicycleRouteProfile::LessCarTraffic,
                            BicycleRouteProfile::Smooth};

// Resets the global profile even if a check fails.
struct ProfileGuard
{
  explicit ProfileGuard(BicycleRouteProfile profile) { SetBicycleRouteProfile(profile); }
  ~ProfileGuard() { SetBicycleRouteProfile(BicycleRouteProfile::Standard); }
};

std::shared_ptr<EdgeEstimator> CreateBicycleEstimator()
{
  return EdgeEstimator::Create(VehicleType::Bicycle, 25.0 /* maxWeightSpeedKMpH */,
                               SpeedKMpH(1.0, 1.0) /* offroadSpeedKMpH */, nullptr /* trafficStash */,
                               nullptr /* dataSource */, nullptr /* numMwmIds */);
}

RoadGeometry MakeRoad(std::optional<HighwayType> type)
{
  return RoadGeometry(false /* oneWay */, 20.0, 18.0, {{0.0, 0.0}, {0.0, 0.01}}, type);
}

// Factors only make roads look slower and add penalties, so the A* heuristic stays admissible.
UNIT_TEST(BicycleRouteProfile_FactorsKeepHeuristicAdmissible)
{
  for (auto const profile : kProfiles)
  {
    auto const & f = GetBicycleRouteFactors(profile);
    TEST_GREATER_OR_EQUAL(f.m_turn, 1.0, ());
    TEST_GREATER_OR_EQUAL(f.m_controlledJunction, 1.0, ());
    TEST_GREATER_OR_EQUAL(f.m_uncontrolledJunction, 1.0, ());
    for (double const speed : {f.m_mainRoadSpeed, f.m_secondaryRoadSpeed, f.m_tertiaryRoadSpeed})
    {
      TEST_GREATER(speed, 0.0, ());
      TEST_LESS_OR_EQUAL(speed, 1.0, ());
    }
  }
}

UNIT_TEST(BicycleRouteProfile_StandardChangesNothing)
{
  auto const & f = GetBicycleRouteFactors(BicycleRouteProfile::Standard);
  TEST_EQUAL(GetRoadPenaltyFactor(f, RoadPenalty::Type::ControlledJunction), 1.0, ());
  TEST_EQUAL(GetRoadSpeedFactor(f, HighwayType::HighwayPrimary), 1.0, ());
  TEST_EQUAL(f.m_turn, 1.0, ());
}

UNIT_TEST(BicycleRouteProfile_SetAndGet)
{
  ProfileGuard guard(BicycleRouteProfile::FewSignals);
  TEST_EQUAL(GetBicycleRouteProfile(), BicycleRouteProfile::FewSignals, ());
  SetBicycleRouteProfile(BicycleRouteProfile::Count);
  TEST_EQUAL(GetBicycleRouteProfile(), BicycleRouteProfile::Standard, ());
}

UNIT_TEST(BicycleRouteProfile_RoadClasses)
{
  auto const & f = GetBicycleRouteFactors(BicycleRouteProfile::LessCarTraffic);
  TEST_LESS(GetRoadSpeedFactor(f, HighwayType::HighwayPrimary), GetRoadSpeedFactor(f, HighwayType::HighwaySecondary),
            ());
  TEST_LESS(GetRoadSpeedFactor(f, HighwayType::HighwaySecondary), GetRoadSpeedFactor(f, HighwayType::HighwayTertiary),
            ());
  TEST_LESS(GetRoadSpeedFactor(f, HighwayType::HighwayTertiary), 1.0, ());
  TEST_EQUAL(GetRoadSpeedFactor(f, HighwayType::HighwayTrunkLink), f.m_mainRoadSpeed, ());
  TEST_EQUAL(GetRoadSpeedFactor(f, HighwayType::HighwayCycleway), 1.0, ());
  TEST_EQUAL(GetRoadSpeedFactor(f, HighwayType::HighwayResidential), 1.0, ());
  TEST_EQUAL(GetRoadSpeedFactor(f, std::nullopt), 1.0, ());
}

UNIT_TEST(BicycleRouteProfile_SignalPenalty)
{
  auto const estimator = CreateBicycleEstimator();
  ProfileGuard guard(BicycleRouteProfile::FewSignals);
  TEST_EQUAL(estimator->GetRoadPenaltyFactor(Purpose::Weight, RoadPenalty::Type::ControlledJunction), 4.0, ());
  TEST_EQUAL(estimator->GetRoadPenaltyFactor(Purpose::Weight, RoadPenalty::Type::Gate), 1.0, ());
  // Travel times stay honest.
  TEST_EQUAL(estimator->GetRoadPenaltyFactor(Purpose::ETA, RoadPenalty::Type::ControlledJunction), 1.0, ());
}

UNIT_TEST(BicycleRouteProfile_TurnPenalty)
{
  auto const estimator = CreateBicycleEstimator();
  auto const road = MakeRoad(HighwayType::HighwayResidential);
  double const standard = estimator->GetTurnPenalty(Purpose::Weight, 90.0, road, road);
  TEST_GREATER(standard, 0.0, ());

  ProfileGuard guard(BicycleRouteProfile::Straight);
  TEST_ALMOST_EQUAL_ABS(estimator->GetTurnPenalty(Purpose::Weight, 90.0, road, road), 4.0 * standard, 1e-9, ());
  TEST_ALMOST_EQUAL_ABS(estimator->GetTurnPenalty(Purpose::ETA, 90.0, road, road), standard, 1e-9, ());
}

UNIT_TEST(BicycleRouteProfile_SegmentWeight)
{
  auto const estimator = CreateBicycleEstimator();
  Segment const segment(0 /* mwmId */, 0 /* featureId */, 0 /* segmentIdx */, true /* forward */);
  auto const primary = MakeRoad(HighwayType::HighwayPrimary);
  auto const cycleway = MakeRoad(HighwayType::HighwayCycleway);
  double const primaryStandard = estimator->CalcSegmentWeight(segment, primary, Purpose::Weight);
  double const cyclewayStandard = estimator->CalcSegmentWeight(segment, cycleway, Purpose::Weight);
  double const primaryEta = estimator->CalcSegmentWeight(segment, primary, Purpose::ETA);

  ProfileGuard guard(BicycleRouteProfile::LessCarTraffic);
  TEST_ALMOST_EQUAL_ABS(estimator->CalcSegmentWeight(segment, primary, Purpose::Weight), primaryStandard / 0.5, 1e-9,
                        ());
  TEST_ALMOST_EQUAL_ABS(estimator->CalcSegmentWeight(segment, cycleway, Purpose::Weight), cyclewayStandard, 1e-9, ());
  TEST_ALMOST_EQUAL_ABS(estimator->CalcSegmentWeight(segment, primary, Purpose::ETA), primaryEta, 1e-9, ());
}
}  // namespace bicycle_route_profile_test
