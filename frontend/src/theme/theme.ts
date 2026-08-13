import { createTheme, type Theme, type ThemeOptions } from '@mui/material/styles'

/**
 * Acme's theme, over MUI's components.
 *
 * Stock Material reads as "a Google product", and the brief judges whether a
 * supplier associates this experience with *Acme*. Four levers carry nearly all
 * of that signal, and all four are changed here: the typeface is not Roboto, the
 * palette is a restrained enterprise set with semantic compliance colours, the
 * radii are tightened, and elevation is replaced by borders.
 *
 * The two surfaces share every component and differ in temperature and density.
 * The supplier portal is for someone who does this twice a year: warmer paper,
 * more air, larger type. The ops console is for a team of four who live in it:
 * cooler, denser, more on screen at once.
 */

export type Surface = 'supplier' | 'ops'

/** Compliance state is a colour the ops team learns to read at a glance. */
export const compliance = {
  compliant: '#2F6F4E',
  expiring: '#9A6212',
  nonCompliant: '#A8342B',
  inReview: '#2F5E8C',
  idle: '#6B7280',
} as const

const ink = {
  900: '#0D2B33',
  800: '#123C47',
  700: '#0F4C5C',
  600: '#286C7C',
  100: '#DCE6E8',
  50: '#EEF3F4',
} as const

const fontStack = [
  '"IBM Plex Sans"',
  '-apple-system',
  'BlinkMacSystemFont',
  '"Segoe UI"',
  'sans-serif',
].join(', ')

function base(surface: Surface): ThemeOptions {
  const dense = surface === 'ops'

  return {
    palette: {
      mode: 'light',
      primary: { main: ink[700], dark: ink[900], light: ink[600], contrastText: '#FFFFFF' },
      secondary: { main: '#4A5C6A' },
      success: { main: compliance.compliant },
      warning: { main: compliance.expiring },
      error: { main: compliance.nonCompliant },
      info: { main: compliance.inReview },
      background: {
        // Warm paper for the brand surface, cool grey for the daily tool.
        default: dense ? '#F2F5F6' : '#FBF9F6',
        paper: '#FFFFFF',
      },
      text: { primary: '#16242B', secondary: '#5A6B73' },
      divider: '#DCE1E3',
    },

    shape: { borderRadius: dense ? 3 : 4 },

    typography: {
      fontFamily: fontStack,
      fontSize: dense ? 13.5 : 15,
      h1: { fontFamily: fontStack, fontSize: dense ? '1.5rem' : '1.9rem', fontWeight: 600, letterSpacing: '-0.02em' },
      h2: { fontFamily: fontStack, fontSize: dense ? '1.2rem' : '1.45rem', fontWeight: 600, letterSpacing: '-0.015em' },
      h3: { fontFamily: fontStack, fontSize: dense ? '1.05rem' : '1.15rem', fontWeight: 600 },
      subtitle2: { fontWeight: 600, letterSpacing: '0.02em' },
      button: { textTransform: 'none', fontWeight: 500 },
      // Labels above values, in the small-caps register an ops tool uses for
      // field names. Set here so no component invents its own.
      overline: {
        fontSize: '0.68rem',
        fontWeight: 600,
        letterSpacing: '0.09em',
        textTransform: 'uppercase',
        color: '#5A6B73',
        lineHeight: 1.8,
      },
    },

    components: {
      MuiCssBaseline: {
        styleOverrides: {
          // Dates and money line up in columns without a monospace font.
          'time, .tabular': { fontVariantNumeric: 'tabular-nums' },
          body: { WebkitFontSmoothing: 'antialiased' },
        },
      },

      // Borders over shadows: an ops lead scanning 300 suppliers needs
      // information density, not a stack of floating cards.
      MuiPaper: {
        defaultProps: { elevation: 0 },
        styleOverrides: {
          root: { backgroundImage: 'none' },
          outlined: { borderColor: '#DCE1E3' },
        },
      },
      MuiCard: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: { root: { borderColor: '#DCE1E3' } },
      },
      MuiAppBar: {
        defaultProps: { elevation: 0, color: 'inherit' },
        styleOverrides: {
          root: {
            backgroundColor: '#FFFFFF',
            borderBottom: '1px solid #DCE1E3',
          },
        },
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: { paddingInline: dense ? 12 : 16 },
          sizeSmall: { paddingInline: 10 },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { borderRadius: 3, fontWeight: 600, fontSize: '0.72rem', letterSpacing: '0.01em' },
          sizeSmall: { height: 22 },
        },
      },
      MuiTextField: {
        defaultProps: { size: dense ? 'small' : 'medium', variant: 'outlined' },
      },
      MuiTableCell: {
        styleOverrides: {
          root: { paddingBlock: dense ? 8 : 12, borderColor: '#E6EAEB' },
          head: {
            fontWeight: 600,
            fontSize: '0.72rem',
            letterSpacing: '0.08em',
            textTransform: 'uppercase',
            color: '#5A6B73',
            backgroundColor: dense ? '#F7F9FA' : '#FFFFFF',
          },
        },
      },
      MuiAlert: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: { root: { borderRadius: dense ? 3 : 4 } },
      },
      MuiTooltip: {
        defaultProps: { arrow: true },
      },
      MuiLink: {
        defaultProps: { underline: 'hover' },
        styleOverrides: { root: { fontWeight: 500 } },
      },
      MuiDialog: {
        defaultProps: { fullWidth: true, maxWidth: 'sm' },
        styleOverrides: { paper: { border: '1px solid #DCE1E3' } },
      },
    },
  }
}

const themes: Record<Surface, Theme> = {
  supplier: createTheme(base('supplier')),
  ops: createTheme(base('ops')),
}

export function acmeTheme(surface: Surface): Theme {
  return themes[surface]
}
