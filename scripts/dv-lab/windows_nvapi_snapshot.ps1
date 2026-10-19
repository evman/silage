# Read-only NVIDIA HDR configuration snapshot. All command-bearing calls use GET.
# Layouts/IDs: NVIDIA/nvapi 70d337db9186e968eab622f7e786de7e437faf3d.
# Driver configuration is not an HDMI/DP packet capture or Dolby conformance test.
param([string]$OutputPath)
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class SilageNvSnapshot {
    [DllImport("nvapi64.dll", EntryPoint="nvapi_QueryInterface", CallingConvention=CallingConvention.Cdecl)]
    static extern IntPtr Query(uint id);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Init();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int EnumGpus([Out] IntPtr[] gpus, out uint count);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Connected(IntPtr gpu, IntPtr displays, ref uint count, uint flags);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int DisplayQuery(uint display, IntPtr data);

    static T Function<T>(uint id) where T : class {
        IntPtr p = Query(id);
        if (p == IntPtr.Zero) throw new Exception("NVAPI function unavailable: " + id.ToString("x8"));
        return Marshal.GetDelegateForFunctionPointer(p, typeof(T)) as T;
    }
    static void Check(int status, string operation) {
        if (status != 0) throw new Exception(operation + ": NVAPI status " + status);
    }
    static IntPtr Buffer(int size, int version) {
        IntPtr p = Marshal.AllocHGlobal(size);
        Marshal.Copy(new byte[size], 0, p, size);
        Marshal.WriteInt32(p, size | (version << 16));
        return p;
    }
    static string Hex(IntPtr p, int size) {
        byte[] bytes = new byte[size]; Marshal.Copy(p, bytes, 0, size);
        return BitConverter.ToString(bytes).Replace("-", "").ToLowerInvariant();
    }

    public static object[] Read() {
        Check(Function<Init>(0x0150e828)(), "Initialize");
        var enumGpus = Function<EnumGpus>(0xe5ac921f);
        var connected = Function<Connected>(0x0078dba2);
        var hdrGet = Function<DisplayQuery>(0x351da224);
        var capsGet = Function<DisplayQuery>(0x84f2a8df);
        var gpus = new IntPtr[64]; uint gpuCount;
        Check(enumGpus(gpus, out gpuCount), "EnumPhysicalGPUs");
        var output = new List<object>();
        for (int g = 0; g < gpuCount; g++) {
            uint count = 0;
            Check(connected(gpus[g], IntPtr.Zero, ref count, 0), "CountConnectedDisplays");
            if (count == 0) continue;
            if (count > 256) throw new Exception("Unexpected display count");
            int capacity = (int)count;
            IntPtr ids = Marshal.AllocHGlobal(capacity * 16);
            try {
                Marshal.Copy(new byte[capacity * 16], 0, ids, capacity * 16);
                for (int i = 0; i < capacity; i++) Marshal.WriteInt32(ids, i * 16, 16 | (3 << 16));
                Check(connected(gpus[g], ids, ref count, 0), "GetConnectedDisplays");
                if (count > capacity) throw new Exception("Display topology changed; repeat snapshot");
                for (int i = 0; i < count; i++) {
                    uint id = unchecked((uint)Marshal.ReadInt32(ids, i * 16 + 8));
                    uint flags = unchecked((uint)Marshal.ReadInt32(ids, i * 16 + 12));
                    var result = new Dictionary<string, object>();
                    result["display_id"] = id;
                    result["active"] = (flags & 4) != 0;
                    result["connected"] = (flags & 64) != 0;
                    result["physically_connected"] = (flags & (1 << 17)) != 0;
                    IntPtr hdr = Buffer(52, 2), caps = Buffer(64, 3);
                    try {
                        // NV_HDR_CMD_GET = 0; the freshly zeroed command stays zero.
                        int status = hdrGet(id, hdr);
                        result["hdr_get_status"] = status;
                        if (status == 0) {
                            result["hdr_color_data_v2_hex"] = Hex(hdr, 52);
                            result["hdr_mode"] = Marshal.ReadInt32(hdr, 8);
                            var mastering = new ushort[12];
                            for (int j = 0; j < mastering.Length; j++)
                                mastering[j] = unchecked((ushort)Marshal.ReadInt16(hdr, 16 + j * 2));
                            result["mastering_primaries_white_luminance_cll_fall"] = mastering;
                            result["color_format_enum"] = Marshal.ReadInt32(hdr, 40);
                            result["dynamic_range_enum"] = Marshal.ReadInt32(hdr, 44);
                            result["bpc_enum"] = Marshal.ReadInt32(hdr, 48);
                        }
                        status = capsGet(id, caps);
                        result["capabilities_get_status"] = status;
                        if (status == 0) {
                            result["hdr_capabilities_v3_hex"] = Hex(caps, 64);
                            result["dolby_vision_capable"] = (Marshal.ReadInt32(caps, 4) & 32) != 0;
                        }
                    } finally { Marshal.FreeHGlobal(hdr); Marshal.FreeHGlobal(caps); }
                    output.Add(result);
                }
            } finally { Marshal.FreeHGlobal(ids); }
        }
        return output.ToArray();
    }
}
'@
$report = [ordered]@{
    timestamp_utc = [DateTime]::UtcNow.ToString('o')
    provenance = 'NVAPI GET driver configuration; not an external link capture'
    panel_dolby_vision_confirmation = 'Must be recorded independently by the operator'
    nvapi_header_revision = '70d337db9186e968eab622f7e786de7e437faf3d'
    displays = @([SilageNvSnapshot]::Read())
}
$json = ConvertTo-Json -InputObject $report -Depth 8
if ($OutputPath) { Set-Content -LiteralPath $OutputPath -Value $json -Encoding UTF8 }
$json
