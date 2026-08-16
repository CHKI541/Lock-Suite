// Reemplazá estos valores con los de tu proyecto real:
// Firebase Console → ⚙️ Configuración del proyecto → General → Tus apps → Configuración del SDK
// (Si todavía no tenés una "app web" registrada en el proyecto, creá una ahí mismo — es gratis y no
// tiene nada que ver con la app de Android, es solo para que este panel pueda hablar con Firebase)

const firebaseConfig = {
  apiKey: "AIzaSyCepVyJpCEyjPbdTOrRxFyqdVpj3bxbdBs",
  authDomain: "looksuite-41866.firebaseapp.com",
  databaseURL: "https://looksuite-41866-default-rtdb.firebaseio.com",
  projectId: "looksuite-41866",
  storageBucket: "looksuite-41866.firebasestorage.app",
  messagingSenderId: "1029474882211",
  appId: "1:1029474882211:web:efcdd9b9539df98f75a516"
};

firebase.initializeApp(firebaseConfig);
