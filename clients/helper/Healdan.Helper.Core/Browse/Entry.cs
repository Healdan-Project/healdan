using System.ComponentModel;
using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Browse;

/// <summary>
/// A row of a window's middle pane (spec §5.8): a folder, a matter or a document, with the columns' text. A document's
/// Edited is when it was last saved, which comes from its versions after the row is shown; a matter's is its last
/// change, which only Recent Matters knows.
/// </summary>
public sealed class Entry : INotifyPropertyChanged
{
    private string _edited = "";

    public Entry(ListItem item, long? me)
    {
        (Folder, Document, Matter, FavoriteId) = (item.Folder, item.Document, item.Matter, item.FavoriteId);
        if (item.Document is { } doc)
        {
            Kind = EntryKind.Document;
            Title = doc.Name;
            Version = doc.FileVersion;
            DocNumber = doc.DocNumber.ToString(System.Globalization.CultureInfo.InvariantCulture);
            MatterText = MatterNumber(doc.Matter?.Display);
            MatterTip = doc.Matter is { } m ? Matters.Line(m) : null;
            Holder = Holds.Text(doc.Hold, me);
            HeldByMe = Holds.ByMe(doc.Hold, me);
        }
        else if (item.Matter is { } matter)
        {
            Kind = EntryKind.Matter;
            Title = matter.Display;
            ClientText = Matters.ClientNote(matter.Client);
        }
        else
        {
            Kind = EntryKind.Folder;
            Title = item.Folder!.Kind == "matter" && item.Folder.Matter is { } m ? m.Display : item.Folder.Name;
            ClientText = item.Folder.Kind == "matter" ? Matters.ClientNote(item.Folder.Matter?.Client) : "";
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public EntryKind Kind { get; }

    public FolderView? Folder { get; }

    public DocumentView? Document { get; }

    public MatterView? Matter { get; }

    /// <summary>The favourite's id, when the row came from Favorites.</summary>
    public long? FavoriteId { get; }

    public string Title { get; }

    /// <summary>A document's content version, as its title shows it (<c>2.1</c>).</summary>
    public string Version { get; } = "";

    public string DocNumber { get; } = "";

    /// <summary>The matter's number, such as <c>998.1</c>.</summary>
    public string MatterText { get; } = "";

    /// <summary>A matter's client after its title, <c>(Northwind Properties)</c>; empty for a folder or document.</summary>
    public string ClientText { get; } = "";

    /// <summary>A document's matter in full, for the Matter column's tooltip; null when it has none.</summary>
    public string? MatterTip { get; }

    /// <summary>"checked out to you on MacBook", "checked out to Jane Smith", or empty.</summary>
    public string Holder { get; } = "";

    public bool HasHolder => Holder.Length > 0;

    public bool HeldByMe { get; }

    public bool IsDocument => Kind == EntryKind.Document;

    public bool IsFolder => Kind != EntryKind.Document;

    public string Edited
    {
        get => _edited;
        set
        {
            if (_edited == value)
                return;
            _edited = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Edited)));
        }
    }

    private static string MatterNumber(string? display)
    {
        if (string.IsNullOrEmpty(display))
            return "";
        var cut = display.IndexOf(" – ", StringComparison.Ordinal);
        return cut > 0 ? display[..cut] : display;
    }
}
