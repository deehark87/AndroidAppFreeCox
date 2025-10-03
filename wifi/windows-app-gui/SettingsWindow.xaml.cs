using System;
using System.Collections.Generic;
using System.Linq;
using System.Management;
using System.Windows;

namespace FreeWifiGui
{
    public partial class SettingsWindow : Window
    {
        private SettingsStore _store;
        public SettingsWindow(SettingsStore store)
        {
            InitializeComponent();
            _store = store;
            LoadInterfaces();
            ChkUseBuiltIn.IsChecked = _store.UseBuiltInMacChanger;
            CbInterfaces.SelectedItem = _store.InterfaceName;
        }

        private void LoadInterfaces()
        {
            var list = new List<string>();
            try
            {
                var searcher = new ManagementObjectSearcher("SELECT * FROM Win32_NetworkAdapter WHERE NetConnectionStatus=2");
                foreach (var obj in searcher.Get())
                {
                    var name = obj["NetConnectionID"] as string;
                    if (!string.IsNullOrEmpty(name)) list.Add(name);
                }
            }
            catch { }
            CbInterfaces.ItemsSource = list;
        }

        private void BtnSave_Click(object sender, RoutedEventArgs e)
        {
            _store.InterfaceName = CbInterfaces.SelectedItem as string ?? _store.InterfaceName;
            _store.UseBuiltInMacChanger = ChkUseBuiltIn.IsChecked == true;
            _store.Save();
            DialogResult = true;
            Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
            Close();
        }
    }
}
