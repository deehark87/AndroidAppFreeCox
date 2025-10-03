using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;

namespace FreeWifiGui
{
    public partial class MainWindow : Window
    {
        private AutomationService _service;
        private SettingsStore _settings;
        public MainWindow()
        {
            InitializeComponent();
            _settings = new SettingsStore(AppDomain.CurrentDomain.BaseDirectory);
            _service = new AutomationService(AppDomain.CurrentDomain.BaseDirectory, Log);
            // Apply settings
            _service.SetInterfaceName(_settings.InterfaceName);
            _service.SetUseBuiltInMacChanger(_settings.UseBuiltInMacChanger);
        }

        private void Log(string line)
        {
            Dispatcher.Invoke(() => {
                TxtLogs.Text = line + "\n" + TxtLogs.Text;
            });
        }

        private async void BtnStart_Click(object sender, RoutedEventArgs e)
        {
            BtnStart.IsEnabled = false;
            BtnStop.IsEnabled = true;
            TxtStatus.Text = "Running";
            await _service.StartAsync();
        }

        private void BtnStop_Click(object sender, RoutedEventArgs e)
        {
            _service.Stop();
            BtnStart.IsEnabled = true;
            BtnStop.IsEnabled = false;
            TxtStatus.Text = "Stopped";
        }

        private void BtnChangeMac_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new OpenFileDialog();
            dlg.Filter = "Executable scripts|*.ps1;*.exe;*.bat|All files|*.*";
            if (dlg.ShowDialog() == true)
            {
                _service.SetMacChangerPath(dlg.FileName);
                Log("Set MAC changer to: " + dlg.FileName);
            }
        }

        private void BtnSettings_Click(object sender, RoutedEventArgs e)
        {
            var win = new SettingsWindow(_settings);
            if (win.ShowDialog() == true)
            {
                // Apply updated settings
                _service.SetInterfaceName(_settings.InterfaceName);
                _service.SetUseBuiltInMacChanger(_settings.UseBuiltInMacChanger);
                Log("Settings saved");
                if (_settings.UseBuiltInMacChanger && !IsElevated())
                {
                    var result = MessageBox.Show("Built-in MAC changer requires admin. Restart as admin?", "Elevation required", MessageBoxButton.YesNo);
                    if (result == MessageBoxResult.Yes)
                    {
                        RelaunchAsAdmin();
                    }
                }
            }
        }

        private bool IsElevated()
        {
            try
            {
                var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
                var principal = new System.Security.Principal.WindowsPrincipal(identity);
                return principal.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
            }
            catch { return false; }
        }

        private void RelaunchAsAdmin()
        {
            try
            {
                var exe = System.Diagnostics.Process.GetCurrentProcess().MainModule.FileName;
                var psi = new System.Diagnostics.ProcessStartInfo(exe)
                {
                    UseShellExecute = true,
                    Verb = "runas"
                };
                System.Diagnostics.Process.Start(psi);
                Application.Current.Shutdown();
            }
            catch (Exception ex)
            {
                Log("Elevation failed: " + ex.Message);
            }
        }
    }
}
