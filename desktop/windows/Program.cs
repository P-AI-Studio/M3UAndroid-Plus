using LibVLCSharp.Shared;

namespace M3UAndroidPlus.Windows;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        Core.Initialize();
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
