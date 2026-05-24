---
name: Linguistic Utility System
colors:
  surface: '#f8f9fa'
  surface-dim: '#d9dadb'
  surface-bright: '#f8f9fa'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f3f4f5'
  surface-container: '#edeeef'
  surface-container-high: '#e7e8e9'
  surface-container-highest: '#e1e3e4'
  on-surface: '#191c1d'
  on-surface-variant: '#45464c'
  inverse-surface: '#2e3132'
  inverse-on-surface: '#f0f1f2'
  outline: '#76777d'
  outline-variant: '#c6c6cd'
  surface-tint: '#575e70'
  primary: '#000000'
  on-primary: '#ffffff'
  primary-container: '#141b2b'
  on-primary-container: '#7d8497'
  inverse-primary: '#c0c6db'
  secondary: '#0058be'
  on-secondary: '#ffffff'
  secondary-container: '#2170e4'
  on-secondary-container: '#fefcff'
  tertiary: '#000000'
  on-tertiary: '#ffffff'
  tertiary-container: '#151c27'
  on-tertiary-container: '#7d8492'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dce2f7'
  primary-fixed-dim: '#c0c6db'
  on-primary-fixed: '#141b2b'
  on-primary-fixed-variant: '#404758'
  secondary-fixed: '#d8e2ff'
  secondary-fixed-dim: '#adc6ff'
  on-secondary-fixed: '#001a42'
  on-secondary-fixed-variant: '#004395'
  tertiary-fixed: '#dce2f3'
  tertiary-fixed-dim: '#c0c7d6'
  on-tertiary-fixed: '#151c27'
  on-tertiary-fixed-variant: '#404754'
  background: '#f8f9fa'
  on-background: '#191c1d'
  surface-variant: '#e1e3e4'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  label-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.01em
  label-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 8px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
  margin-mobile: 20px
  gutter-mobile: 12px
---

## Brand & Style

The design system is anchored in a **Utility-First Minimalism** aesthetic, specifically tailored for high-speed information retrieval and cognitive clarity. The brand personality is professional, objective, and efficient—functioning as a transparent tool rather than a decorative interface.

The visual direction draws from **Corporate Modernism**, emphasizing extreme legibility and a reduced cognitive load. Key characteristics include:
- **Spatial Precision:** Generous whitespace is used to isolate linguistic data points, preventing visual crowding during translation tasks.
- **Functional Contrast:** A stark black-on-white typographic foundation ensures accessibility and focus.
- **Soft Geometry:** While the typography is rigid and systematic, the container language uses soft, rounded corners to make the "utility" feel approachable and modern rather than industrial.

## Colors

The palette is strictly monochromatic with a single functional accent color. This ensures that the user's attention is never diverted by non-essential decorative elements.

- **Primary (#111827):** A deep, ink-like gray used for high-contrast typography and primary interactive states.
- **Secondary (#3B82F6):** A functional blue reserved for text selection, active toggle states, and subtle call-to-actions.
- **Neutrals:** The background utilizes a pure white (`#FFFFFF`) to maximize contrast, while `#F3F4F6` and `#F9FAFB` define container surfaces and secondary groupings.
- **Semantic:** Success and error states should remain muted, using low-saturation variants to maintain the minimalist tone.

## Typography

The typography uses **Inter**, a typeface specifically designed for computer screens. It provides a systematic, neutral voice that prioritizes clarity over character.

- **Scale:** A tight typographic scale is used to maintain a professional "SaaS" or "Utility" feel.
- **Weights:** Use 'SemiBold' (600) for headlines and 'Medium' (500) for labels to provide hierarchy without the heaviness of 'Bold'.
- **Vertical Rhythm:** Line heights are set at 1.5x for body text to ensure comfortable reading during long-form definitions or translation paragraphs.
- **Mobile Adjustments:** For mobile views, the `display-lg` should scale down to 32px to ensure word-wraps do not break the layout of dictionary entries.

## Layout & Spacing

The design system utilizes a **fluid grid** centered on an 8px base unit. This ensures all elements align to a predictable rhythmic scale.

- **Grid:** A 4-column layout for mobile is standard. For dictionary list views, content should span the full width minus the 20px side margins.
- **Density:** High whitespace is preferred in "Discovery" modes (landing pages), while "Search & Results" modes should increase density to `spacing.sm` between list items to allow more data on screen.
- **Touch Targets:** All interactive elements must maintain a minimum hit area of 44x44px, regardless of their visual size.

## Elevation & Depth

To maintain a clean, flat aesthetic, this design system avoids heavy shadows and skeuomorphism. Depth is communicated through **Tonal Layers** and **Ambient Shadows**.

- **Surface 0 (Background):** Pure white (#FFFFFF).
- **Surface 1 (Cards/Inputs):** Off-white (#F9FAFB) with a subtle 1px border (#E5E7EB).
- **Shadows:** Only used for floating action buttons or primary cards. Use an extra-diffused shadow: `0px 4px 20px rgba(0, 0, 0, 0.04)`.
- **Active State:** When an item is pressed, it should subtly scale down (0.98x) rather than changing shadow depth.

## Shapes

The shape language is consistently **Rounded**, softening the "utility" aspect to make the app feel more accessible for daily use.

- **Base Radius:** 0.5rem (8px) for buttons and input fields.
- **Large Radius:** 1rem (16px) for main content cards and bottom sheets, as seen in the reference image.
- **Full Radius:** Use pill-shapes for "Language Switcher" tags and category chips.

## Components

### Buttons
- **Primary:** Solid `#111827` with white text. No shadow.
- **Secondary:** Transparent background with `#111827` 1px border.
- **Ghost:** Text-only for less frequent actions like "Clear History."

### Input Fields (Search)
The search bar is the most critical component. It should be a large, Surface 1 (`#F9FAFB`) container with a leading search icon and a clear button. When focused, the border transitions to the primary color.

### Dictionary Cards
Word entries are housed in Surface 1 containers with `rounded-lg` corners. Use `label-sm` for parts of speech (e.g., "NOUN") in the tertiary gray to provide metadata without distracting from the main word.

### Language Toggle
A centered component using a simple "Source → Target" layout. Icons should be minimal line-art, avoiding high-detail flag icons to maintain the professional aesthetic.

### Chips/Tags
Small, pill-shaped containers with `label-sm` text. Used for synonyms, categories, or recent search history tags.