import { useRef, type ReactNode } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";

interface HorizontalRowProps {
  title: string;
  children: ReactNode;
}

export function HorizontalRow({ title, children }: HorizontalRowProps) {
  const scrollRef = useRef<HTMLDivElement>(null);

  const scrollBy = (offset: number) => {
    scrollRef.current?.scrollBy({ left: offset, behavior: "smooth" });
  };

  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold tracking-tight text-[#F8FAFC]">
          {title}
        </h2>
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => scrollBy(-540)}
            className="rounded-lg border border-[#283347] bg-[#141923] p-1.5 text-[#94A3B8] transition-colors hover:border-[#38BDF8] hover:text-[#F8FAFC]"
          >
            <ChevronLeft className="h-4 w-4" />
          </button>
          <button
            onClick={() => scrollBy(540)}
            className="rounded-lg border border-[#283347] bg-[#141923] p-1.5 text-[#94A3B8] transition-colors hover:border-[#38BDF8] hover:text-[#F8FAFC]"
          >
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      </div>

      <div
        ref={scrollRef}
        className="no-scrollbar flex gap-4 overflow-x-auto pb-2"
      >
        {children}
      </div>
    </section>
  );
}
