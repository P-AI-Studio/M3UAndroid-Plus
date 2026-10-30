using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using LibVLCSharp.Shared;
using LibVLCSharp.WinForms;

namespace M3UAndroidPlus.Windows;

internal sealed class MainForm : Form
{
    private readonly HttpClient _http;
    private readonly LibVLC _libVlc = new();
    private readonly MediaPlayer _player;
    private readonly VideoView _video;
    private readonly ListBox _groups = new();
    private readonly ListBox _channels = new();
    private readonly TextBox _url = new();
    private readonly TextBox _search = new();
    private readonly Label _status = new();
    private readonly ProgressBar _progress = new();
    private readonly List<PlaylistGroup> _playlists = new();
    private CancellationTokenSource? _testCancellation;

    private static string DataDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "M3UAndroidPlusWindows");
    private static string DataFile => Path.Combine(DataDirectory, "playlists.json");

    public MainForm()
    {
        Text = "M3UAndroid Plus — Windows";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(1000, 650);
        Size = new Size(1440, 900);
        Font = new Font("Segoe UI", 9.5f);
        BackColor = Color.FromArgb(20, 23, 30);
        ForeColor = Color.FromArgb(235, 239, 245);

        _http = new HttpClient(new SocketsHttpHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.All,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5)
        }) { Timeout = TimeSpan.FromSeconds(15) };

        _player = new MediaPlayer(_libVlc);
        _video = new VideoView
        {
            Dock = DockStyle.Fill,
            MediaPlayer = _player,
            BackColor = Color.Black
        };

        BuildLayout();
        _groups.SelectedIndexChanged += (_, _) => RefreshChannels();
        _channels.DoubleClick += (_, _) => PlaySelectedChannel();
        _search.TextChanged += (_, _) => RefreshChannels();
        Shown += async (_, _) => await LoadSavedAsync();
        FormClosing += (_, _) =>
        {
            _testCancellation?.Cancel();
            _player.Stop();
            _player.Dispose();
            _libVlc.Dispose();
            _http.Dispose();
        };
    }

    private void BuildLayout()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(10),
            BackColor = BackColor
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(root);

        var toolbar = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            WrapContents = true,
            Padding = new Padding(0, 0, 0, 8),
            BackColor = BackColor
        };
        _url.Width = 350;
        _url.PlaceholderText = "M3U playlist URL";
        StyleInput(_url);
        _search.Width = 220;
        _search.PlaceholderText = "Search this group";
        StyleInput(_search);

        toolbar.Controls.Add(_url);
        toolbar.Controls.Add(MakeButton("Add group", async (_, _) => await AddGroupFromInputAsync()));
        toolbar.Controls.Add(MakeButton("Bulk import", async (_, _) => await BulkImportAsync()));
        toolbar.Controls.Add(MakeButton("Refresh", async (_, _) => await RefreshSelectedAsync()));
        toolbar.Controls.Add(MakeButton("Test all channels", async (_, _) => await TestSelectedGroupAsync()));
        toolbar.Controls.Add(MakeButton("Play", (_, _) => PlaySelectedChannel()));
        toolbar.Controls.Add(_search);
        toolbar.Controls.Add(MakeButton("⋮", (_, _) => ShowActionsMenu()));
        root.Controls.Add(toolbar, 0, 0);

        var outer = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Vertical,
            SplitterDistance = 250,
            Panel1MinSize = 190,
            Panel2MinSize = 600,
            BackColor = Color.FromArgb(45, 50, 62)
        };
        StyleList(_groups);
        _groups.Dock = DockStyle.Fill;
        outer.Panel1.Controls.Add(_groups);

        var inner = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Vertical,
            SplitterDistance = 330,
            Panel1MinSize = 240,
            Panel2MinSize = 350,
            BackColor = Color.FromArgb(45, 50, 62)
        };
        StyleList(_channels);
        _channels.Dock = DockStyle.Fill;
        inner.Panel1.Controls.Add(_channels);
        inner.Panel2.Controls.Add(_video);
        outer.Panel2.Controls.Add(inner);
        root.Controls.Add(outer, 0, 1);

        var footer = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Padding = new Padding(0, 8, 0, 0)
        };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 260));
        _status.AutoSize = true;
        _status.Text = "Ready. Add a playlist or import several groups.";
        _status.ForeColor = Color.FromArgb(190, 200, 215);
        _progress.Dock = DockStyle.Fill;
        _progress.Visible = false;
        footer.Controls.Add(_status, 0, 0);
        footer.Controls.Add(_progress, 1, 0);
        root.Controls.Add(footer, 0, 2);
    }

    private void ShowActionsMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("Add new group", null, async (_, _) => await AddGroupFromInputAsync());
        menu.Items.Add("Import independent groups…", null, async (_, _) => await BulkImportAsync());
        menu.Items.Add("Test every channel", null, async (_, _) => await TestSelectedGroupAsync());
        menu.Items.Add("Refresh selected group", null, async (_, _) => await RefreshSelectedAsync());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Sort groups A–Z", null, async (_, _) =>
        {
            _playlists.Sort((a, b) => StringComparer.CurrentCultureIgnoreCase.Compare(a.Title, b.Title));
            RebuildGroups();
            await SaveAsync();
        });
        menu.Items.Add("Move group up", null, async (_, _) => await MoveGroupAsync(-1));
        menu.Items.Add("Move group down", null, async (_, _) => await MoveGroupAsync(1));
        menu.Items.Add("Remove group", null, async (_, _) => await RemoveGroupAsync());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Stop connection test", null, (_, _) => _testCancellation?.Cancel());
        menu.Show(Cursor.Position);
    }

    private static void StyleInput(TextBox box)
    {
        box.BackColor = Color.FromArgb(34, 39, 49);
        box.ForeColor = Color.White;
        box.BorderStyle = BorderStyle.FixedSingle;
        box.Margin = new Padding(4, 6, 4, 6);
    }

    private static void StyleList(ListBox list)
    {
        list.BackColor = Color.FromArgb(28, 32, 41);
        list.ForeColor = Color.FromArgb(235, 239, 245);
        list.BorderStyle = BorderStyle.None;
        list.IntegralHeight = false;
        list.Font = new Font("Segoe UI", 10f);
    }

    private static Button MakeButton(string text, EventHandler handler)
    {
        var button = new Button
        {
            Text = text,
            AutoSize = true,
            Height = 34,
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(49, 94, 150),
            ForeColor = Color.White,
            Margin = new Padding(4, 6, 4, 6),
            Padding = new Padding(10, 2, 10, 2)
        };
        button.FlatAppearance.BorderSize = 0;
        button.Click += handler;
        return button;
    }

    private async Task LoadSavedAsync()
    {
        try
        {
            Directory.CreateDirectory(DataDirectory);
            if (File.Exists(DataFile))
            {
                await using var stream = File.OpenRead(DataFile);
                var stored = await JsonSerializer.DeserializeAsync<List<PlaylistGroup>>(stream);
                if (stored is not null)
                {
                    _playlists.Clear();
                    _playlists.AddRange(stored);
                }
            }
            RebuildGroups();
            SetStatus("Loaded " + _playlists.Count + " groups.");
        }
        catch (Exception ex)
        {
            SetStatus("Could not load groups: " + ex.Message);
        }
    }

    private async Task SaveAsync()
    {
        Directory.CreateDirectory(DataDirectory);
        var temp = DataFile + ".tmp";
        await using (var stream = File.Create(temp))
        {
            await JsonSerializer.SerializeAsync(stream, _playlists, new JsonSerializerOptions { WriteIndented = true });
        }
        File.Move(temp, DataFile, true);
    }

    private async Task AddGroupFromInputAsync()
    {
        var url = _url.Text.Trim();
        if (!IsHttpUrl(url))
        {
            SetStatus("Enter a valid HTTP or HTTPS playlist URL.");
            return;
        }
        await AddGroupAsync(url, "Group " + (_playlists.Count + 1));
        _url.Clear();
    }

    private async Task BulkImportAsync()
    {
        using var dialog = new Form
        {
            Text = "Import separate playlist groups",
            StartPosition = FormStartPosition.CenterParent,
            Size = new Size(760, 500),
            BackColor = BackColor,
            ForeColor = ForeColor
        };
        var input = new TextBox
        {
            Multiline = true,
            AcceptsReturn = true,
            ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill,
            Font = new Font("Consolas", 10f)
        };
        StyleInput(input);
        var help = new Label
        {
            Text = "One complete M3U playlist URL per line. Every line becomes its own numbered group.",
            Dock = DockStyle.Top,
            Height = 38,
            ForeColor = ForeColor
        };
        var bottom = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 50, FlowDirection = FlowDirection.RightToLeft };
        bottom.Controls.Add(MakeButton("Import groups", (_, _) => dialog.DialogResult = DialogResult.OK));
        dialog.Controls.Add(input);
        dialog.Controls.Add(help);
        dialog.Controls.Add(bottom);
        if (dialog.ShowDialog(this) != DialogResult.OK) return;

        var valid = input.Lines.Select(x => x.Trim())
            .Where(IsHttpUrl)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(100)
            .ToList();
        var invalid = input.Lines.Count(x => !string.IsNullOrWhiteSpace(x) && !IsHttpUrl(x.Trim()));
        if (valid.Count == 0)
        {
            SetStatus("No valid playlist URLs were entered.");
            return;
        }
        foreach (var url in valid)
            await AddGroupAsync(url, "Group " + (_playlists.Count + 1));
        SetStatus("Imported " + valid.Count + " independent groups; skipped " + invalid + " invalid lines.");
    }

    private async Task AddGroupAsync(string url, string title)
    {
        if (_playlists.Any(x => string.Equals(x.SourceUrl, url, StringComparison.OrdinalIgnoreCase)))
        {
            SetStatus("That playlist is already registered.");
            return;
        }
        SetStatus("Loading " + title + "…");
        try
        {
            var text = await DownloadTextAsync(url, CancellationToken.None);
            var channels = ParseM3u(text);
            if (channels.Count == 0)
                throw new InvalidDataException("No playable M3U entries were found.");
            var group = new PlaylistGroup { Title = title, SourceUrl = url, Channels = channels };
            _playlists.Add(group);
            RebuildGroups(group);
            await SaveAsync();
            SetStatus(title + ": loaded " + channels.Count + " channels.");
        }
        catch (Exception ex)
        {
            SetStatus(title + " was not added: " + ex.Message);
        }
    }

    private async Task RefreshSelectedAsync()
    {
        var group = SelectedGroup;
        if (group is null) return;
        SetStatus("Refreshing " + group.Title + "…");
        try
        {
            var text = await DownloadTextAsync(group.SourceUrl, CancellationToken.None);
            var channels = ParseM3u(text);
            if (channels.Count == 0)
                throw new InvalidDataException("The refreshed playlist contains no channels.");
            group.Channels = channels;
            RebuildGroups(group);
            await SaveAsync();
            SetStatus("Refreshed " + group.Title + ": " + channels.Count + " channels.");
        }
        catch (Exception ex)
        {
            SetStatus("Refresh failed: " + ex.Message);
        }
    }

    private async Task MoveGroupAsync(int direction)
    {
        var index = _groups.SelectedIndex;
        var target = index + direction;
        if (index < 0 || target < 0 || target >= _playlists.Count) return;
        (_playlists[index], _playlists[target]) = (_playlists[target], _playlists[index]);
        RebuildGroups(_playlists[target]);
        await SaveAsync();
    }

    private async Task RemoveGroupAsync()
    {
        var group = SelectedGroup;
        if (group is null) return;
        if (MessageBox.Show(this, "Remove " + group.Title + " and its local channel list?",
                "Remove group", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;
        _playlists.Remove(group);
        RebuildGroups();
        await SaveAsync();
        SetStatus("Removed " + group.Title + ".");
    }

    private PlaylistGroup? SelectedGroup => _groups.SelectedItem as PlaylistGroup;

    private void RebuildGroups(PlaylistGroup? selected = null)
    {
        _groups.BeginUpdate();
        _groups.Items.Clear();
        foreach (var group in _playlists) _groups.Items.Add(group);
        if (selected is not null) _groups.SelectedItem = selected;
        else if (_groups.Items.Count > 0) _groups.SelectedIndex = 0;
        _groups.EndUpdate();
        RefreshChannels();
    }

    private void RefreshChannels()
    {
        var group = SelectedGroup;
        var query = _search.Text.Trim();
        _channels.BeginUpdate();
        _channels.Items.Clear();
        if (group is not null)
        {
            foreach (var channel in group.Channels.Where(x =>
                         string.IsNullOrWhiteSpace(query) ||
                         x.Title.Contains(query, StringComparison.CurrentCultureIgnoreCase) ||
                         x.Category.Contains(query, StringComparison.CurrentCultureIgnoreCase)))
                _channels.Items.Add(channel);
        }
        _channels.EndUpdate();
    }

    private void PlaySelectedChannel()
    {
        if (_channels.SelectedItem is not ChannelItem channel) return;
        try
        {
            using var media = new Media(_libVlc, channel.Url, FromType.FromLocation);
            if (!string.IsNullOrWhiteSpace(channel.UserAgent))
                media.AddOption(":http-user-agent=" + channel.UserAgent);
            _player.Play(media);
            SetStatus("Playing " + channel.Title + ".");
        }
        catch (Exception ex)
        {
            SetStatus("Playback failed: " + ex.Message);
        }
    }

    private async Task TestSelectedGroupAsync()
    {
        var group = SelectedGroup;
        if (group is null || group.Channels.Count == 0) return;
        _testCancellation?.Cancel();
        _testCancellation?.Dispose();
        _testCancellation = new CancellationTokenSource();
        var token = _testCancellation.Token;
        var channels = group.Channels.ToList();
        var runId = Guid.NewGuid();
        Tag = runId;

        var done = 0;
        var online = 0;
        var offline = 0;
        var restricted = 0;
        var inconclusive = 0;
        _progress.Visible = true;
        _progress.Minimum = 0;
        _progress.Maximum = channels.Count;
        _progress.Value = 0;
        SetStatus("Testing every channel in " + group.Title + "…");

        var reporter = new Progress<ChannelItem>(_ =>
        {
            if (IsDisposed || !IsHandleCreated || !Equals(Tag, runId)) return;
            var current = Volatile.Read(ref done);
            _progress.Value = Math.Min(current, _progress.Maximum);
            _channels.Invalidate();
            _status.Text = "Progress " + current + "/" + channels.Count + " (" +
                (current * 100 / channels.Count) + "%) · Online " + online + " · Offline " +
                offline + " · Restricted " + restricted + " · Check manually " + inconclusive;
        });

        try
        {
            await Parallel.ForEachAsync(channels, new ParallelOptions
            {
                MaxDegreeOfParallelism = 3,
                CancellationToken = token
            }, async (channel, cancellation) =>
            {
                var result = await ProbeChannelAsync(channel, cancellation);
                if (result.Outcome == ProbeOutcome.Inconclusive)
                {
                    await Task.Delay(500, cancellation);
                    result = await ProbeChannelAsync(channel, cancellation);
                }
                channel.Outcome = result.Outcome;
                channel.Status = result.Description;
                switch (result.Outcome)
                {
                    case ProbeOutcome.Online: Interlocked.Increment(ref online); break;
                    case ProbeOutcome.Offline: Interlocked.Increment(ref offline); break;
                    case ProbeOutcome.Restricted: Interlocked.Increment(ref restricted); break;
                    default: Interlocked.Increment(ref inconclusive); break;
                }
                Interlocked.Increment(ref done);
                ((IProgress<ChannelItem>)reporter).Report(channel);
            });

            if (Equals(Tag, runId))
                SetStatus("Finished " + done + "/" + channels.Count + " · Online " + online +
                    " · Offline " + offline + " · Restricted " + restricted + " · Check manually " + inconclusive + ".");
        }
        catch (OperationCanceledException)
        {
            if (Equals(Tag, runId))
                SetStatus("Test stopped after " + done + "/" + channels.Count + ".");
        }
        finally
        {
            if (Equals(Tag, runId)) _progress.Visible = false;
        }
    }

    private async Task<ProbeResult> ProbeChannelAsync(ChannelItem channel, CancellationToken cancellation)
    {
        if (!Uri.TryCreate(channel.Url, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
            return new ProbeResult(ProbeOutcome.Inconclusive, "Unsupported protocol for HTTP check.");

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            request.Headers.Range = new RangeHeaderValue(0, 8191);
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("*/*"));
            if (!string.IsNullOrWhiteSpace(channel.UserAgent))
                request.Headers.TryAddWithoutValidation("User-Agent", channel.UserAgent);

            using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation);
            var status = (int)response.StatusCode;
            if (status is 401 or 403 or 407 or 429)
                return new ProbeResult(ProbeOutcome.Restricted, "HTTP " + status + ": authentication or provider restriction.");
            if (status is 404 or 410)
                return new ProbeResult(ProbeOutcome.Offline, "HTTP " + status + ": endpoint not found.");
            if (status >= 500)
                return new ProbeResult(ProbeOutcome.Inconclusive, "Provider returned HTTP " + status + "; retry or verify in the player.");

            await using var stream = await response.Content.ReadAsStreamAsync(cancellation);
            var bytes = new byte[8192];
            var count = await stream.ReadAsync(bytes.AsMemory(0, bytes.Length), cancellation);
            if (status < 200 || status >= 300)
                return new ProbeResult(ProbeOutcome.Inconclusive, "HTTP " + status + ".");
            if (count == 0)
                return new ProbeResult(ProbeOutcome.Inconclusive, "Empty response.");

            var type = response.Content.Headers.ContentType?.MediaType ?? "";
            var preview = Encoding.UTF8.GetString(bytes, 0, count).TrimStart();
            if (type.Contains("html", StringComparison.OrdinalIgnoreCase) ||
                type.Contains("json", StringComparison.OrdinalIgnoreCase) ||
                type.Contains("xml", StringComparison.OrdinalIgnoreCase) ||
                preview.StartsWith("<!doctype html", StringComparison.OrdinalIgnoreCase) ||
                preview.StartsWith("<html", StringComparison.OrdinalIgnoreCase) ||
                preview.StartsWith("{", StringComparison.Ordinal) ||
                preview.StartsWith("[", StringComparison.Ordinal) ||
                preview.StartsWith("error", StringComparison.OrdinalIgnoreCase) ||
                preview.StartsWith("unauthorized", StringComparison.OrdinalIgnoreCase))
                return new ProbeResult(ProbeOutcome.Inconclusive, "Server returned a web or structured error page.");

            if (preview.StartsWith("#EXTM3U", StringComparison.OrdinalIgnoreCase))
            {
                var mediaLine = preview.Split('\n')
                    .Select(x => x.Trim())
                    .FirstOrDefault(x => x.Length > 0 && !x.StartsWith('#'));
                if (mediaLine is null)
                    return new ProbeResult(ProbeOutcome.Inconclusive, "Manifest loaded; no media segment found.");
                var finalUri = response.RequestMessage?.RequestUri ?? uri;
                if (!Uri.TryCreate(finalUri, mediaLine, out var segmentUri))
                    return new ProbeResult(ProbeOutcome.Inconclusive, "Could not resolve first HLS segment.");
                using var segmentRequest = new HttpRequestMessage(HttpMethod.Get, segmentUri);
                segmentRequest.Headers.Range = new RangeHeaderValue(0, 4095);
                if (!string.IsNullOrWhiteSpace(channel.UserAgent))
                    segmentRequest.Headers.TryAddWithoutValidation("User-Agent", channel.UserAgent);
                using var segmentResponse = await _http.SendAsync(segmentRequest, HttpCompletionOption.ResponseHeadersRead, cancellation);
                await using var segmentStream = await segmentResponse.Content.ReadAsStreamAsync(cancellation);
                var segmentBuffer = new byte[4096];
                var segmentBytes = await segmentStream.ReadAsync(segmentBuffer.AsMemory(0, segmentBuffer.Length), cancellation);
                var segmentStatus = (int)segmentResponse.StatusCode;
                var segmentType = segmentResponse.Content.Headers.ContentType?.MediaType ?? "";
                if (segmentStatus is >= 200 and < 300 && segmentBytes > 0 &&
                    LooksLikeMediaPayload(segmentType, segmentBuffer, segmentBytes))
                    return new ProbeResult(ProbeOutcome.Online, "HLS manifest and first media segment returned media-like bytes.");
                if (segmentStatus is 401 or 403 or 429)
                    return new ProbeResult(ProbeOutcome.Restricted, "First HLS segment returned HTTP " + segmentStatus + ".");
                if (segmentStatus is 404 or 410)
                    return new ProbeResult(ProbeOutcome.Offline, "First HLS segment returned HTTP " + segmentStatus + ".");
                if (segmentStatus >= 500)
                    return new ProbeResult(ProbeOutcome.Inconclusive, "First HLS segment returned transient HTTP " + segmentStatus + ".");
                return new ProbeResult(ProbeOutcome.Inconclusive, "First HLS segment was not confirmed.");
            }

            if (!LooksLikeMediaPayload(type, bytes, count))
                return new ProbeResult(ProbeOutcome.Inconclusive, "Bytes were returned, but they do not look like a recognized media stream.");
            return new ProbeResult(ProbeOutcome.Online, "HTTP " + status + " · " + type);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { throw; }
        catch (OperationCanceledException) { return new ProbeResult(ProbeOutcome.Inconclusive, "Connection timed out; result is inconclusive."); }
        catch (HttpRequestException ex) { return new ProbeResult(ProbeOutcome.Inconclusive, "Network check inconclusive: " + ex.GetType().Name); }
        catch (Exception ex) { return new ProbeResult(ProbeOutcome.Inconclusive, ex.GetType().Name); }
    }

    private static bool LooksLikeMediaPayload(string contentType, byte[] bytes, int count)
    {
        var type = contentType ?? "";
        if (type.StartsWith("video/", StringComparison.OrdinalIgnoreCase) ||
            type.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) ||
            type.Contains("mp2t", StringComparison.OrdinalIgnoreCase) ||
            type.Contains("mp4", StringComparison.OrdinalIgnoreCase) ||
            type.Contains("mpeg", StringComparison.OrdinalIgnoreCase))
            return true;
        if (count >= 8)
        {
            if (bytes[0] == 0x47 || (count > 188 && bytes[188] == 0x47) ||
                (count > 376 && bytes[376] == 0x47))
                return true;
            var box = Encoding.ASCII.GetString(bytes, 4, 4);
            if (box is "ftyp" or "moof" or "styp") return true;
            var signature = Encoding.ASCII.GetString(bytes, 0, 4);
            if (signature is "ID3" or "OggS" or "FLV ") return true;
        }
        var sample = Math.Min(count, 2048);
        if (sample == 0) return false;
        var printable = 0;
        for (var i = 0; i < sample; i++)
        {
            var value = bytes[i];
            if (value is 9 or 10 or 13 || value is >= 32 and <= 126 || value >= 160)
                printable++;
        }
        return (double)printable / sample < 0.85;
    }

    private async Task<string> DownloadTextAsync(string url, CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation);
        response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentLength is > 5_000_000)
            throw new InvalidDataException("Playlist is larger than 5 MB.");
        await using var stream = await response.Content.ReadAsStreamAsync(cancellation);
        using var memory = new MemoryStream();
        var buffer = new byte[32 * 1024];
        while (memory.Length < 5_000_000)
        {
            var amount = await stream.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, 5_000_000 - memory.Length)), cancellation);
            if (amount <= 0) break;
            await memory.WriteAsync(buffer.AsMemory(0, amount), cancellation);
        }
        return Encoding.UTF8.GetString(memory.ToArray());
    }

    private static List<ChannelItem> ParseM3u(string content)
    {
        var channels = new List<ChannelItem>();
        var title = "";
        var category = "";
        string? userAgent = null;
        foreach (var raw in content.Replace("\r", "").Split('\n'))
        {
            var line = raw.Trim();
            if (line.StartsWith("#EXTVLCOPT:http-user-agent=", StringComparison.OrdinalIgnoreCase))
            {
                userAgent = line["#EXTVLCOPT:http-user-agent=".Length..].Trim();
                continue;
            }
            if (line.StartsWith("#EXTINF", StringComparison.OrdinalIgnoreCase))
            {
                var comma = line.IndexOf(',');
                title = comma >= 0 ? line[(comma + 1)..].Trim() : "Channel " + (channels.Count + 1);
                var match = Regex.Match(line, "group-title=[\"" + "'" + "](?<category>.*?)[\"" + "'" + "]", RegexOptions.IgnoreCase);
                category = match.Success ? match.Groups["category"].Value : "";
                continue;
            }
            if (line.Length == 0 || line.StartsWith('#')) continue;
            var parts = line.Split('|', 2);
            var streamUrl = parts[0].Trim();
            if (!IsHttpUrl(streamUrl)) continue;
            string? customAgent = null;
            if (parts.Length > 1)
            {
                var match = Regex.Match(parts[1], "(?:user-agent|ua)=([^&;]+)", RegexOptions.IgnoreCase);
                if (match.Success) customAgent = Uri.UnescapeDataString(match.Groups[1].Value);
            }
            channels.Add(new ChannelItem
            {
                Title = string.IsNullOrWhiteSpace(title) ? "Channel " + (channels.Count + 1) : title,
                Url = streamUrl,
                Category = category,
                UserAgent = customAgent ?? userAgent
            });
            title = "";
            category = "";
            userAgent = null;
        }
        return channels;
    }

    private static bool IsHttpUrl(string value) =>
        Uri.TryCreate(value, UriKind.Absolute, out var uri) &&
        (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps);

    private void SetStatus(string message)
    {
        if (InvokeRequired)
        {
            BeginInvoke((Action)(() => SetStatus(message)));
            return;
        }
        _status.Text = message;
    }
}

internal sealed class PlaylistGroup
{
    public string Title { get; set; } = "";
    public string SourceUrl { get; set; } = "";
    public List<ChannelItem> Channels { get; set; } = new();
    public override string ToString() => Title + " · " + Channels.Count;
}

internal sealed class ChannelItem
{
    public string Title { get; set; } = "";
    public string Url { get; set; } = "";
    public string Category { get; set; } = "";
    public string? UserAgent { get; set; }
    public ProbeOutcome? Outcome { get; set; }
    public string? Status { get; set; }
    public override string ToString() => Outcome is null ? Title : "[" + Outcome + "] " + Title;
}

internal enum ProbeOutcome { Online, Offline, Restricted, Inconclusive }
internal sealed record ProbeResult(ProbeOutcome Outcome, string Description);
