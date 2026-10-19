// Windows 10/11 includes the .NET Framework used by this small launcher.
// The application, Java runtime and mpv are embedded as a ZIP resource.
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;

internal static class PortableLauncher
{
    private const string Payload = "Silage.Payload.zip";

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            string home = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
            string app = Unpack(home);
            var start = new ProcessStartInfo(Path.Combine(app, "silage.exe"));
            start.UseShellExecute = false;
            start.WorkingDirectory = app;
            start.Arguments = JoinArguments(args);
            start.EnvironmentVariables["SILAGE_PORTABLE_HOME"] = home;
            // A user's developer Java settings must not affect the bundled runtime.
            start.EnvironmentVariables.Remove("JAVA_HOME");
            start.EnvironmentVariables.Remove("JAVA_TOOL_OPTIONS");
            start.EnvironmentVariables.Remove("JDK_JAVA_OPTIONS");
            using (Process child = Process.Start(start))
            {
                child.WaitForExit();
                return child.ExitCode;
            }
        }
        catch (Exception error)
        {
            string message = "Silage could not start. Keep the EXE in a writable folder.\n\n" + error.Message;
            Console.Error.WriteLine(message);
            if (Array.IndexOf(args, "--check") < 0 && Array.IndexOf(args, "--version") < 0)
                MessageBox.Show(message, "Silage", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 2;
        }
    }

    private static string Unpack(string home)
    {
        string version;
        using (Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream(Payload))
        using (SHA256 hash = SHA256.Create())
            version = BitConverter.ToString(hash.ComputeHash(payload)).Replace("-", "").ToLowerInvariant();

        string cache = Path.Combine(home, ".silage-runtime");
        Directory.CreateDirectory(cache);
        string destination = Path.Combine(cache, version);
        string app = Path.Combine(destination, "Silage");
        using (FileStream guard = Lock(Path.Combine(cache, "extract.lock")))
        {
            if (File.Exists(Path.Combine(destination, "complete")) &&
                File.Exists(Path.Combine(app, "silage.exe")) &&
                File.Exists(Path.Combine(app, "libmpv-2.dll")) &&
                File.Exists(Path.Combine(app, "runtime", "bin", "server", "jvm.dll")))
                return app;

            string staging = destination + ".tmp-" + Process.GetCurrentProcess().Id;
            try
            {
                if (Directory.Exists(staging)) Directory.Delete(staging, true);
                Directory.CreateDirectory(staging);
                string prefix = Path.GetFullPath(staging) + Path.DirectorySeparatorChar;
                using (Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream(Payload))
                using (var zip = new ZipArchive(payload, ZipArchiveMode.Read))
                {
                    foreach (ZipArchiveEntry entry in zip.Entries)
                    {
                        string path = Path.GetFullPath(Path.Combine(staging, entry.FullName));
                        if (!path.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                            throw new InvalidDataException("Invalid path in bundled application.");
                        if (String.IsNullOrEmpty(entry.Name))
                            Directory.CreateDirectory(path);
                        else
                        {
                            Directory.CreateDirectory(Path.GetDirectoryName(path));
                            using (Stream input = entry.Open())
                            using (FileStream output = File.Create(path)) input.CopyTo(output);
                        }
                    }
                }
                File.WriteAllText(Path.Combine(staging, "complete"), version);
                if (Directory.Exists(destination)) Directory.Delete(destination, true);
                Directory.Move(staging, destination);
            }
            finally
            {
                if (Directory.Exists(staging)) Directory.Delete(staging, true);
            }
        }
        return app;
    }

    private static FileStream Lock(string path)
    {
        for (int attempt = 0; ; attempt++)
        {
            try { return new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
            catch (IOException)
            {
                if (attempt >= 120) throw;
                Thread.Sleep(500);
            }
        }
    }

    // Windows command-line quoting, including embedded quotes and trailing slashes.
    private static string JoinArguments(string[] args)
    {
        var result = new StringBuilder();
        foreach (string arg in args)
        {
            if (result.Length > 0) result.Append(' ');
            result.Append('"');
            int slashes = 0;
            foreach (char c in arg)
            {
                if (c == '\\') { slashes++; continue; }
                result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
                result.Append(c);
                slashes = 0;
            }
            result.Append('\\', slashes * 2);
            result.Append('"');
        }
        return result.ToString();
    }
}
