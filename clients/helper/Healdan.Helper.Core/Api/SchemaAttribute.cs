namespace Healdan.Helper.Core.Api;

/// <summary>Names the OpenAPI schema a record mirrors, so a test can check the record reads no field the contract lacks.</summary>
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct, AllowMultiple = false)]
public sealed class SchemaAttribute(string name) : Attribute
{
    public string Name { get; } = name;
}
