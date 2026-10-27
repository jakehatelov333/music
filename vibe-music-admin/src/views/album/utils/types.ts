interface ArtistOption {
  artistId: number;
  artistName: string;
}

interface FormItemProps {
  formTitle: string;
  albumId?: number;
  coverUrl?: string;
  artistId?: number;
  artistName?: string;
  albumName: string;
  releaseDate: string;
  description: string;
  status?: "DRAFT" | "PUBLISHED";
}

interface FormProps {
  formInline: FormItemProps;
  artistOptions: ArtistOption[];
}

export type { ArtistOption, FormItemProps, FormProps };
