using System.Globalization;
using System.Text.Json;

namespace AndroidFakeRun.Core;

internal static class RouteParser
{
    public static IReadOnlyList<GeoPoint> Parse(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) throw new FormatException("请先粘贴路线坐标。");
        var json = text.Trim();
        if (!json.StartsWith('[')) json = "[" + json.TrimEnd(',') + "]";

        using var document = JsonDocument.Parse(json, new JsonDocumentOptions { AllowTrailingCommas = true });
        if (document.RootElement.ValueKind != JsonValueKind.Array) throw new FormatException("路线必须是坐标数组。");
        var points = new List<GeoPoint>();
        foreach (var item in document.RootElement.EnumerateArray())
        {
            if (!item.TryGetProperty("lat", out var latValue) || !item.TryGetProperty("lng", out var lngValue))
                throw new FormatException("每个坐标都必须包含 lat 和 lng。");
            var lat = ReadDouble(latValue);
            var lng = ReadDouble(lngValue);
            if (lat is < -90 or > 90 || lng is < -180 or > 180)
                throw new FormatException("路线中存在超出范围的经纬度。");
            points.Add(new GeoPoint(lat, lng));
        }
        if (points.Count < 2) throw new FormatException("路线至少需要两个坐标点。");
        return points;
    }

    private static double ReadDouble(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Number => value.GetDouble(),
        JsonValueKind.String when double.TryParse(value.GetString(), NumberStyles.Float,
            CultureInfo.InvariantCulture, out var number) => number,
        _ => throw new FormatException("经纬度必须是数字。")
    };
}
