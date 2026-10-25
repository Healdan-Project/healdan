using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Channel;

/// <summary>The contents of <c>channel.json</c>, and the way a second helper process hands a request to the running one.</summary>
public sealed record ChannelInfo(int Port, string Secret, int Pid)
{
    public const string SecretHeader = "X-Healdan-Secret";

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    /// <summary>The info file, or null when it is missing or damaged.</summary>
    public static ChannelInfo? Read(HelperPaths p)
    {
        try
        {
            var info = JsonSerializer.Deserialize<ChannelInfo>(File.ReadAllText(p.Channel), Json);
            return info is { Port: > 0, Pid: > 0 } && !string.IsNullOrEmpty(info.Secret) ? info : null;
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>Writes the file in one step, private to the user from the moment it exists.</summary>
    internal static void Write(HelperPaths p, ChannelInfo info)
    {
        Directory.CreateDirectory(p.Support);
        var temp = p.Channel + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            var options = new FileStreamOptions { Mode = FileMode.CreateNew, Access = FileAccess.Write };
            if (!OperatingSystem.IsWindows())
                options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
            using (var stream = new FileStream(temp, options))
                stream.Write(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(info, Json)));
            File.Move(temp, p.Channel, overwrite: true);
        }
        finally
        {
            File.Delete(temp);
        }
    }

    /// <summary>
    /// Sends <c>POST /v1/&lt;route&gt;</c> to the helper that is running. False when there is no info file, its process
    /// is gone, nothing answers within <paramref name="timeout"/>, or it answers with an error.
    /// </summary>
    public static bool TryForward(HelperPaths p, string route, JsonObject body, TimeSpan timeout)
    {
        var info = Read(p);
        if (info is null || !IsAlive(info.Pid))
            return false;
        try
        {
            using var http = new HttpClient { Timeout = timeout };
            using var request = new HttpRequestMessage(HttpMethod.Post, $"http://127.0.0.1:{info.Port}/v1/{route}")
            {
                Content = new StringContent(body.ToJsonString(), Encoding.UTF8, new MediaTypeHeaderValue("application/json")),
            };
            request.Headers.Add(SecretHeader, info.Secret);
            using var response = http.Send(request);
            return response.IsSuccessStatusCode;
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or OperationCanceledException or IOException)
        {
            return false;
        }
    }

    private static bool IsAlive(int pid)
    {
        try
        {
            using var process = Process.GetProcessById(pid);
            return !process.HasExited;
        }
        catch (ArgumentException)
        {
            return false;
        }
    }
}
