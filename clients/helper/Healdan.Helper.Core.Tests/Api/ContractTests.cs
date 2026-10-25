using System.Reflection;
using System.Text.Json;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using YamlDotNet.Serialization;

namespace Healdan.Helper.Core.Tests.Api;

/// <summary>Checks the client against the API's hand-written contract, so it never calls what the contract lacks.</summary>
public class ContractTests
{
    /// <summary>Every route the client builds itself. The windows' later paths are added here as they're written.</summary>
    public static TheoryData<string, string> Routes => new()
    {
        { "get", "/v1/server" },
        { "post", "/v1/auth/tokens" },
        { "delete", "/v1/auth/tokens/current" },
        { "get", "/v1/documents/{docNumber}" },
        { "post", "/v1/documents/{docNumber}/checkout" },
        { "delete", "/v1/documents/{docNumber}/checkout" },
        { "get", "/v1/documents/{docNumber}/content" },
        { "post", "/v1/documents/{docNumber}/versions" },
        { "post", "/v1/documents" },
        { "patch", "/v1/documents/{docNumber}/properties" },
        // The windows' (Task 17).
        { "get", "/v1/me" },
        { "get", "/v1/me/checkouts" },
        { "get", "/v1/me/favorites" },
        { "post", "/v1/me/favorites" },
        { "delete", "/v1/me/favorites/{id}" },
        { "get", "/v1/documents/recent" },
        { "get", "/v1/matters/recent" },
        { "get", "/v1/matters/mine" },
        { "get", "/v1/folders/recent" },
        { "get", "/v1/folders/{id}" },
        { "get", "/v1/folders/{id}/children" },
        { "post", "/v1/folders" },
        { "get", "/v1/folders/{folderId}/form" },
        { "get", "/v1/search" },
        { "get", "/v1/lookup" },
        { "get", "/v1/users" },
        { "get", "/v1/documents/{docNumber}/properties" },
        { "get", "/v1/documents/{docNumber}/versions" },
        { "get", "/v1/documents/{docNumber}/thumbnail" },
    };

    [Fact]
    public void EveryPlaceInTheLeftPanesIsARouteTheClientCalls()
    {
        var routes = Routes.Select(r => ((string)r[0], (string)r[1])).ToHashSet();
        foreach (var place in Place.ForSave.Concat(Place.ForOpen).Where(p => p.Path is not null))
            Assert.Contains(("get", "/" + place.Path), routes);
    }

    private static readonly Lazy<Dictionary<object, object>> Contract = new(() =>
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "healdan-api")))
            dir = dir.Parent;
        Assert.NotNull(dir);
        var file = Path.Combine(dir.FullName, "healdan-api", "src", "main", "resources", "healdan", "api", "openapi.yaml");
        return new DeserializerBuilder().Build().Deserialize<Dictionary<object, object>>(File.ReadAllText(file));
    });

    private static Dictionary<object, object> Map(object? o) => (Dictionary<object, object>)o!;

    private static Dictionary<object, object> Schemas =>
        Map(Map(Contract.Value["components"])["schemas"]);

    [Theory]
    [MemberData(nameof(Routes))]
    public void EveryCallIsInTheContract(string method, string template)
    {
        var paths = Map(Contract.Value["paths"]);
        Assert.True(paths.ContainsKey(template), $"{template} is not in the contract");
        Assert.True(Map(paths[template]).ContainsKey(method), $"{method.ToUpperInvariant()} {template} is not in the contract");
    }

    [Fact]
    public void TheMultipartPartsAreTheOnesTheContractNames()
    {
        var paths = Map(Contract.Value["paths"]);
        Assert.Equal(["version", "file"], PartNames(paths["/v1/documents/{docNumber}/versions"]));
        Assert.Equal(["document", "file"], PartNames(paths["/v1/documents"]));
    }

    private static List<string> PartNames(object path)
    {
        var post = Map(Map(path)["post"]);
        var schema = Map(Map(Map(Map(post["requestBody"])["content"])["multipart/form-data"])["schema"]);
        return Map(schema["properties"]).Keys.Cast<string>().ToList();
    }

    [Fact]
    public void EveryFieldReadIsInTheSchema()
    {
        var checkedTypes = 0;
        foreach (var type in typeof(SchemaAttribute).Assembly.GetTypes())
        {
            var attr = type.GetCustomAttribute<SchemaAttribute>();
            if (attr is null) continue;
            checkedTypes++;
            Assert.True(Schemas.ContainsKey(attr.Name), $"{type.Name}: no schema {attr.Name}");
            var known = PropertyNames(Schemas[attr.Name]);
            foreach (var property in type.GetProperties(BindingFlags.Public | BindingFlags.Instance))
            {
                if (property.GetCustomAttribute<System.Text.Json.Serialization.JsonIgnoreAttribute>() is not null) continue;
                var json = JsonNamingPolicy.CamelCase.ConvertName(property.Name);
                Assert.True(known.Contains(json), $"{type.Name}.{property.Name} ({json}) is not in schema {attr.Name}");
            }
        }
        Assert.True(checkedTypes >= 10, "the models should carry [Schema] attributes");
    }

    private static HashSet<string> PropertyNames(object schema)
    {
        var names = new HashSet<string>();
        var map = Map(schema);
        if (map.TryGetValue("$ref", out var reference))
            names.UnionWith(PropertyNames(Schemas[((string)reference).Split('/')[^1]]));
        if (map.TryGetValue("allOf", out var all))
            foreach (var part in (List<object>)all) names.UnionWith(PropertyNames(part));
        if (map.TryGetValue("properties", out var props))
            names.UnionWith(Map(props).Keys.Cast<string>());
        return names;
    }
}
