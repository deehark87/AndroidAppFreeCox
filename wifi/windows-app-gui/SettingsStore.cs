using System;
using System.IO;
using System.Text.Json;

public class SettingsStore
{
    public string InterfaceName { get; set; } = "Wi-Fi";
    public bool UseBuiltInMacChanger { get; set; } = true;
    public bool AutoRestart { get; set; } = true;

    private string _path;
    public SettingsStore(string baseDir)
    {
        _path = Path.Combine(baseDir, "settings.json");
        Load();
    }

    public void Load()
    {
        try
        {
            if (!File.Exists(_path)) return;
            var text = File.ReadAllText(_path);
            var s = JsonSerializer.Deserialize<SettingsStore>(text);
            if (s != null)
            {
                InterfaceName = s.InterfaceName;
                UseBuiltInMacChanger = s.UseBuiltInMacChanger;
                AutoRestart = s.AutoRestart;
            }
        }
        catch { }
    }

    public void Save()
    {
        try
        {
            var text = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(_path, text);
        }
        catch { }
    }
}
