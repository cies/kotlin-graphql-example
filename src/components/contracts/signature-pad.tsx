"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";

interface SignaturePadProps {
  onChange: (svg: string | null) => void;
}

export function SignaturePad({ onChange }: SignaturePadProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [drawing, setDrawing] = useState(false);
  const [paths, setPaths] = useState<string[]>([]);
  const currentPath = useRef<Array<{ x: number; y: number }>>([]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.lineWidth = 2;
    ctx.strokeStyle = "#111827";
    ctx.lineCap = "round";

    for (const pathValue of paths) {
      const path = pathValue.trim();
      const commands = path.trim().split(/\s+/);
      if (commands.length < 3) continue;
      ctx.beginPath();
      for (let i = 0; i < commands.length; i += 3) {
        const cmd = commands[i];
        const x = Number(commands[i + 1]);
        const y = Number(commands[i + 2]);
        if (cmd === "M") ctx.moveTo(x, y);
        if (cmd === "L") ctx.lineTo(x, y);
      }
      ctx.stroke();
    }

    if (paths.length === 0) {
      onChange(null);
    } else {
      const d = paths.join(" ");
      onChange(
        `<svg viewBox="0 0 500 150" xmlns="http://www.w3.org/2000/svg"><path d="${d}" stroke="#111827" fill="none" stroke-width="2" stroke-linecap="round" /></svg>`
      );
    }
  }, [paths, onChange]);

  function getPoint(e: React.PointerEvent<HTMLCanvasElement>) {
    const rect = e.currentTarget.getBoundingClientRect();
    return {
      x: (e.clientX - rect.left) * (500 / rect.width),
      y: (e.clientY - rect.top) * (150 / rect.height),
    };
  }

  return (
    <div className="space-y-2">
      <canvas
        ref={canvasRef}
        width={500}
        height={150}
        className="w-full rounded-md border border-[var(--border)] bg-white"
        onPointerDown={(e) => {
          const p = getPoint(e);
          currentPath.current = [p];
          setDrawing(true);
        }}
        onPointerMove={(e) => {
          if (!drawing) return;
          const p = getPoint(e);
          currentPath.current.push(p);
          const canvas = canvasRef.current;
          const ctx = canvas?.getContext("2d");
          if (!ctx) return;
          const previous = currentPath.current[currentPath.current.length - 2];
          ctx.beginPath();
          ctx.moveTo(previous.x, previous.y);
          ctx.lineTo(p.x, p.y);
          ctx.stroke();
        }}
        onPointerUp={() => {
          setDrawing(false);
          if (currentPath.current.length > 1) {
            const d = currentPath.current
              .map((point, index) => `${index === 0 ? "M" : "L"} ${point.x} ${point.y}`)
              .join(" ");
            setPaths((prev) => [...prev, d]);
          }
          currentPath.current = [];
        }}
        onPointerLeave={() => setDrawing(false)}
      />
      <Button type="button" variant="outline" size="sm" onClick={() => setPaths([])}>
        Clear signature
      </Button>
    </div>
  );
}
