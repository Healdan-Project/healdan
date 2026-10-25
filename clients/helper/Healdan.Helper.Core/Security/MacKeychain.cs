using System.Runtime.InteropServices;
using System.Text;

namespace Healdan.Helper.Core.Security;

/// <summary>
/// The macOS Keychain through Security.framework, as the trial did. The <c>SecKeychain*</c> calls are deprecated but
/// work, and the signed helper reads back its own item with no prompt; don't move to <c>SecItem*</c> without asking.
/// </summary>
public sealed class MacKeychain(string service = "Healdan Helper") : IKeychain
{
    private const string Security = "/System/Library/Frameworks/Security.framework/Security";
    private const string CoreFoundation = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private const int Success = 0;
    private const int ItemNotFound = -25300;
    private const int DuplicateItem = -25299;

    private readonly byte[] _service = Encoding.UTF8.GetBytes(service);

    public string? Load(string account)
    {
        var (status, secret, item) = Find(account);
        if (status == ItemNotFound) return null;
        Check(status, "read");
        CFRelease(item);
        return secret;
    }

    public void Save(string account, string secret)
    {
        var accountBytes = Encoding.UTF8.GetBytes(account);
        var secretBytes = Encoding.UTF8.GetBytes(secret);
        var status = SecKeychainAddGenericPassword(IntPtr.Zero, (uint)_service.Length, _service, (uint)accountBytes.Length,
            accountBytes, (uint)secretBytes.Length, secretBytes, out var added);
        if (status == Success)
        {
            CFRelease(added);
            return;
        }
        if (status != DuplicateItem)
            Check(status, "store");

        var (found, _, item) = Find(account);
        Check(found, "find");
        try
        {
            Check(SecKeychainItemModifyAttributesAndData(item, IntPtr.Zero, (uint)secretBytes.Length, secretBytes), "update");
        }
        finally
        {
            CFRelease(item);
        }
    }

    public void Delete(string account)
    {
        var (status, _, item) = Find(account);
        if (status == ItemNotFound) return;
        Check(status, "find");
        try
        {
            Check(SecKeychainItemDelete(item), "delete");
        }
        finally
        {
            CFRelease(item);
        }
    }

    /// <summary>Finds the item. On success the caller releases <c>item</c>.</summary>
    private (int Status, string? Secret, IntPtr Item) Find(string account)
    {
        var accountBytes = Encoding.UTF8.GetBytes(account);
        var status = SecKeychainFindGenericPassword(IntPtr.Zero, (uint)_service.Length, _service, (uint)accountBytes.Length,
            accountBytes, out var length, out var data, out var item);
        if (status != Success) return (status, null, IntPtr.Zero);
        try
        {
            var bytes = new byte[length];
            Marshal.Copy(data, bytes, 0, (int)length);
            return (status, Encoding.UTF8.GetString(bytes), item);
        }
        finally
        {
            SecKeychainItemFreeContent(IntPtr.Zero, data);
        }
    }

    /// <summary>The message names the status only, never the secret.</summary>
    private static void Check(int status, string what)
    {
        if (status != Success)
            throw new InvalidOperationException($"The macOS Keychain refused to {what} the sign-in (status {status}).");
    }

    [DllImport(Security)]
    private static extern int SecKeychainAddGenericPassword(IntPtr keychain, uint serviceLength, byte[] service,
        uint accountLength, byte[] account, uint passwordLength, byte[] password, out IntPtr item);

    [DllImport(Security)]
    private static extern int SecKeychainFindGenericPassword(IntPtr keychainOrArray, uint serviceLength, byte[] service,
        uint accountLength, byte[] account, out uint passwordLength, out IntPtr password, out IntPtr item);

    [DllImport(Security)]
    private static extern int SecKeychainItemModifyAttributesAndData(IntPtr item, IntPtr attributes, uint length, byte[] data);

    [DllImport(Security)]
    private static extern int SecKeychainItemDelete(IntPtr item);

    [DllImport(Security)]
    private static extern int SecKeychainItemFreeContent(IntPtr attributes, IntPtr data);

    [DllImport(CoreFoundation)]
    private static extern void CFRelease(IntPtr cf);
}
