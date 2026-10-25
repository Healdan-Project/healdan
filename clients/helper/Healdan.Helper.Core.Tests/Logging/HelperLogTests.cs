using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Tests.Logging;

public sealed class HelperLogTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("healdan-log-").FullName;

    public void Dispose() => Directory.Delete(_root, true);

    [Fact]
    public void WritesLinesWithTheirLevel()
    {
        var path = Path.Combine(_root, "Logs", "helper.log");
        var log = new HelperLog(path);

        log.Info("Starting");
        log.Warn("A warning");
        log.Error("It failed", new IOException("disk full"));

        var lines = File.ReadAllLines(path);
        Assert.Equal(3, lines.Length);
        Assert.EndsWith(" INFO Starting", lines[0]);
        Assert.EndsWith(" WARN A warning", lines[1]);
        Assert.EndsWith(" ERROR It failed (IOException: disk full)", lines[2]);
    }

    [Fact]
    public void KeepsOneLineToAMessage()
    {
        var path = Path.Combine(_root, "helper.log");
        new HelperLog(path).Info("first\nERROR forged\r\tline");

        var line = Assert.Single(File.ReadAllLines(path));
        Assert.EndsWith(" INFO first ERROR forged  line", line);
    }

    [Fact]
    public void IsCappedSoItCantGrowWithoutBound()
    {
        var path = Path.Combine(_root, "helper.log");
        var log = new HelperLog(path, maxBytes: 2_000);

        for (var i = 0; i < 500; i++)
            log.Info($"line {i} " + new string('x', 40));

        Assert.True(new FileInfo(path).Length <= 2_100, $"{new FileInfo(path).Length} bytes");
        Assert.True(new FileInfo(path + ".1").Length <= 2_100);
        Assert.Equal([path, path + ".1"], Directory.GetFiles(_root).Order());
        Assert.Contains("line 499 ", File.ReadAllText(path));
    }
}
