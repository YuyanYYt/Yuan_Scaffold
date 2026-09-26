import React from 'react';
import { createRoot } from 'react-dom/client';
import AssetPage from './AssetPage';
import './style.css';

createRoot(document.getElementById('root')!).render(<React.StrictMode><AssetPage /></React.StrictMode>);
