#pragma once

#include <cstdint>
#include <span>
#include <vector>

#include <volk.h>

namespace pt {

// The uncompressed format a BC-compressed texture is decoded to when the GPU driver cannot sample BC formats (most Android
// drivers): RGBA8 for BC1, BC2, BC3 and BC7 (sRGB kept), R8 for BC4, RG8 for BC5 (signed kept) and RGBA16F for BC6H.
// VK_FORMAT_UNDEFINED for any format that is not BC.
VkFormat BcFallbackFormat(VkFormat format);

// Decodes one mip level of a BC texture (width x height texels, the data in 4x4 blocks) into the fallback format's texels,
// tightly packed. False when the format is not BC or the data is shorter than the level needs.
bool DecodeBcMip(VkFormat format, uint32_t width, uint32_t height, std::span<const uint8_t> data, std::vector<uint8_t>& out);

}
