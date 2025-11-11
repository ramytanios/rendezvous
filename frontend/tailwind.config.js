// tailwind.config.js
module.exports = {
  purge: [],
  darkMode: false, // or 'media' or 'class'
  theme: {
    extend: {
      fontFamily: {
        digital: ['"Orbitron"', 'monospace'],
      },
    },
  },
  variants: {
    extend: {},
  },
  plugins: [],
    content: [
      "./index.html",
      "./src/**/*.{js,ts,jsx,tsx,html,scala}"
  ],
}
