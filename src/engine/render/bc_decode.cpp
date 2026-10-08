#include "engine/render/bc_decode.h"

#include <algorithm>
#include <cstring>

#define BCDEC_IMPLEMENTATION
#define BCDEC_BC4BC5_PRECISE
#include <bcdec.h>

namespace pt {
namespace {

enum class Kind { None, Bc1, Bc1Opaque, Bc2, Bc3, Bc4, Bc5, Bc6h, Bc7 };

struct BcInfo {
    Kind kind = Kind::None;
    uint32_t block_bytes = 0;
    uint32_t texel_bytes = 0;
    VkFormat out = VK_FORMAT_UNDEFINED;
    bool is_signed = false;
};

BcInfo Info(VkFormat format) {
    switch (format) {
    case VK_FORMAT_BC1_RGB_UNORM_BLOCK: return {Kind::Bc1Opaque, 8, 4, VK_FORMAT_R8G8B8A8_UNORM};
    case VK_FORMAT_BC1_RGB_SRGB_BLOCK: return {Kind::Bc1Opaque, 8, 4, VK_FORMAT_R8G8B8A8_SRGB};
    case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: return {Kind::Bc1, 8, 4, VK_FORMAT_R8G8B8A8_UNORM};
    case VK_FORMAT_BC1_RGBA_SRGB_BLOCK: return {Kind::Bc1, 8, 4, VK_FORMAT_R8G8B8A8_SRGB};
    case VK_FORMAT_BC2_UNORM_BLOCK: return {Kind::Bc2, 16, 4, VK_FORMAT_R8G8B8A8_UNORM};
    case VK_FORMAT_BC2_SRGB_BLOCK: return {Kind::Bc2, 16, 4, VK_FORMAT_R8G8B8A8_SRGB};
    case VK_FORMAT_BC3_UNORM_BLOCK: return {Kind::Bc3, 16, 4, VK_FORMAT_R8G8B8A8_UNORM};
    case VK_FORMAT_BC3_SRGB_BLOCK: return {Kind::Bc3, 16, 4, VK_FORMAT_R8G8B8A8_SRGB};
    case VK_FORMAT_BC4_UNORM_BLOCK: return {Kind::Bc4, 8, 1, VK_FORMAT_R8_UNORM};
    case VK_FORMAT_BC4_SNORM_BLOCK: return {Kind::Bc4, 8, 1, VK_FORMAT_R8_SNORM, true};
    case VK_FORMAT_BC5_UNORM_BLOCK: return {Kind::Bc5, 16, 2, VK_FORMAT_R8G8_UNORM};
    case VK_FORMAT_BC5_SNORM_BLOCK: return {Kind::Bc5, 16, 2, VK_FORMAT_R8G8_SNORM, true};
    case VK_FORMAT_BC6H_UFLOAT_BLOCK: return {Kind::Bc6h, 16, 8, VK_FORMAT_R16G16B16A16_SFLOAT};
    case VK_FORMAT_BC6H_SFLOAT_BLOCK: return {Kind::Bc6h, 16, 8, VK_FORMAT_R16G16B16A16_SFLOAT, true};
    case VK_FORMAT_BC7_UNORM_BLOCK: return {Kind::Bc7, 16, 4, VK_FORMAT_R8G8B8A8_UNORM};
    case VK_FORMAT_BC7_SRGB_BLOCK: return {Kind::Bc7, 16, 4, VK_FORMAT_R8G8B8A8_SRGB};
    default: return {};
    }
}

// one 4x4 block into `block` (rows of 4 texels in the output format)
void DecodeBlock(const BcInfo& info, const uint8_t* in, uint8_t* block) {
    const int pitch = static_cast<int>(4 * info.texel_bytes);
    switch (info.kind) {
    case Kind::Bc1Opaque:
        bcdec_bc1(in, block, pitch);
        // BC1 without alpha: the 3-colour mode's fourth entry is opaque black, not transparent
        for (int i = 0; i < 16; ++i) block[i * 4 + 3] = 255;
        break;
    case Kind::Bc1: bcdec_bc1(in, block, pitch); break;
    case Kind::Bc2: bcdec_bc2(in, block, pitch); break;
    case Kind::Bc3: bcdec_bc3(in, block, pitch); break;
    case Kind::Bc4: bcdec_bc4(in, block, pitch, info.is_signed ? 1 : 0); break;
    case Kind::Bc5: bcdec_bc5(in, block, pitch, info.is_signed ? 1 : 0); break;
    case Kind::Bc6h: {
        // bcdec writes RGB halfs; the texture is RGBA16F with alpha 1.0
        uint16_t rgb[16 * 3];
        bcdec_bc6h_half(in, rgb, 4 * 3, info.is_signed ? 1 : 0);
        auto* rgba = reinterpret_cast<uint16_t*>(block);
        for (int i = 0; i < 16; ++i) {
            rgba[i * 4 + 0] = rgb[i * 3 + 0];
            rgba[i * 4 + 1] = rgb[i * 3 + 1];
            rgba[i * 4 + 2] = rgb[i * 3 + 2];
            rgba[i * 4 + 3] = 0x3C00;
        }
        break;
    }
    case Kind::Bc7: bcdec_bc7(in, block, pitch); break;
    case Kind::None: break;
    }
}

}

VkFormat BcFallbackFormat(VkFormat format) { return Info(format).out; }

bool DecodeBcMip(VkFormat format, uint32_t width, uint32_t height, std::span<const uint8_t> data, std::vector<uint8_t>& out) {
    const BcInfo info = Info(format);
    if (info.kind == Kind::None || width == 0 || height == 0) {
        return false;
    }
    const uint32_t blocks_x = (width + 3) / 4;
    const uint32_t blocks_y = (height + 3) / 4;
    if (data.size() < size_t(blocks_x) * blocks_y * info.block_bytes) {
        return false;
    }
    out.assign(size_t(width) * height * info.texel_bytes, 0);
    alignas(16) uint8_t block[4 * 4 * 8];
    const uint8_t* in = data.data();
    for (uint32_t by = 0; by < blocks_y; ++by) {
        for (uint32_t bx = 0; bx < blocks_x; ++bx, in += info.block_bytes) {
            DecodeBlock(info, in, block);
            const uint32_t x0 = bx * 4;
            const uint32_t columns = std::min(4u, width - x0);
            for (uint32_t row = 0; row < 4; ++row) {
                const uint32_t y = by * 4 + row;
                if (y >= height) break;
                std::memcpy(out.data() + (size_t(y) * width + x0) * info.texel_bytes, block + row * 4 * info.texel_bytes,
                            size_t(columns) * info.texel_bytes);
            }
        }
    }
    return true;
}

}
