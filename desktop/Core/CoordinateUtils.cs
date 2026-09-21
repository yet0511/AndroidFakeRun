namespace AndroidFakeRun.Core;

internal static class CoordinateUtils
{
    private const double XPi = Math.PI * 3000.0 / 180.0;
    private const double Pi = Math.PI;
    private const double A = 6378245.0;
    private const double E = 0.00669342162296594323;
    private const double EarthRadius = 6378137;

    public static GeoPoint Bd09ToWgs84(GeoPoint point)
    {
        var x = point.Longitude - 0.0065;
        var y = point.Latitude - 0.006;
        var z = Math.Sqrt(x * x + y * y) - 0.00002 * Math.Sin(y * XPi);
        var theta = Math.Atan2(y, x) - 0.000003 * Math.Cos(x * XPi);
        var gcjLat = z * Math.Sin(theta);
        var gcjLng = z * Math.Cos(theta);
        var dLat = TransformLatitude(gcjLat - 35.0, gcjLng - 105.0);
        var dLng = TransformLongitude(gcjLat - 35.0, gcjLng - 105.0);
        var radLat = gcjLat / 180.0 * Pi;
        var magic = 1 - E * Math.Pow(Math.Sin(radLat), 2);
        var sqrtMagic = Math.Sqrt(magic);
        dLng = dLng * 180.0 / (A / sqrtMagic * Math.Cos(radLat) * Pi);
        dLat = dLat * 180.0 / (A * (1 - E) / (magic * sqrtMagic) * Pi);
        return new GeoPoint(gcjLat * 2 - (gcjLat + dLat), gcjLng * 2 - (gcjLng + dLng));
    }

    public static double Distance(GeoPoint a, GeoPoint b)
    {
        var latA = ToRadians(a.Latitude);
        var latB = ToRadians(b.Latitude);
        var deltaLat = latA - latB;
        var deltaLng = ToRadians(a.Longitude) - ToRadians(b.Longitude);
        return 2 * Math.Asin(Math.Sqrt(Math.Pow(Math.Sin(deltaLat / 2), 2) +
            Math.Cos(latA) * Math.Cos(latB) * Math.Pow(Math.Sin(deltaLng / 2), 2))) * EarthRadius;
    }

    public static IReadOnlyList<GeoPoint> InterpolateRoute(IReadOnlyList<GeoPoint> source, double metersPerSecond)
    {
        if (source.Count < 2) throw new ArgumentException("路线至少需要两个坐标点。");
        var result = new List<GeoPoint> { source[0] };
        for (var i = 0; i < source.Count - 1;)
        {
            var j = i + 1;
            var distance = Distance(source[i], source[j]);
            while (j < source.Count - 1 && distance <= metersPerSecond)
            {
                j++;
                distance = Distance(source[i], source[j]);
            }

            var segments = Math.Max(1, (int)Math.Ceiling(distance / metersPerSecond));
            for (var part = 1; part <= segments; part++)
            {
                var ratio = (double)part / segments;
                result.Add(new GeoPoint(
                    source[i].Latitude + (source[j].Latitude - source[i].Latitude) * ratio,
                    source[i].Longitude + (source[j].Longitude - source[i].Longitude) * ratio));
            }
            i = j;
        }
        return result;
    }

    private static double ToRadians(double value) => value * Pi / 180d;

    private static double TransformLatitude(double latitude, double longitude)
    {
        var value = -100.0 + 2.0 * longitude + 3.0 * latitude + 0.2 * latitude * latitude +
                    0.1 * longitude * latitude + 0.2 * Math.Sqrt(Math.Abs(longitude));
        value += (20.0 * Math.Sin(6.0 * longitude * Pi) + 20.0 * Math.Sin(2.0 * longitude * Pi)) * 2.0 / 3.0;
        value += (20.0 * Math.Sin(latitude * Pi) + 40.0 * Math.Sin(latitude / 3.0 * Pi)) * 2.0 / 3.0;
        return value + (160.0 * Math.Sin(latitude / 12.0 * Pi) + 320 * Math.Sin(latitude * Pi / 30.0)) * 2.0 / 3.0;
    }

    private static double TransformLongitude(double latitude, double longitude)
    {
        var value = 300.0 + longitude + 2.0 * latitude + 0.1 * longitude * longitude +
                    0.1 * longitude * latitude + 0.1 * Math.Sqrt(Math.Abs(longitude));
        value += (20.0 * Math.Sin(6.0 * longitude * Pi) + 20.0 * Math.Sin(2.0 * longitude * Pi)) * 2.0 / 3.0;
        value += (20.0 * Math.Sin(longitude * Pi) + 40.0 * Math.Sin(longitude / 3.0 * Pi)) * 2.0 / 3.0;
        return value + (150.0 * Math.Sin(longitude / 12.0 * Pi) + 300.0 * Math.Sin(longitude / 30.0 * Pi)) * 2.0 / 3.0;
    }
}

internal readonly record struct GeoPoint(double Latitude, double Longitude);
